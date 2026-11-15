package com.oriah.payment.confirmation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Taking back a confirmation keyed wrongly, and seeing the money that has no outcome yet (Phase 7). A confirmation that has not paid a sale can be
 * VOIDED by anyone who may confirm, with a reason: it frees the reference (so the corrected confirmation can use it), closes the money held for
 * it, and is announced so finance reverses the fee. One that paid a sale is never edited: correcting it is Phase 12's. The list of confirmations
 * still waiting for order to answer is how staff notice money nothing has acted on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class VoidAndPendingHttpTest {

    private static final String CONFIRMATIONS = "/api/v1/admin/payments/confirmations";
    private static final String PROBLEMS = "https://oriah.dev/problems/";
    private static final String WHY = "keyed against the wrong order";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfirmationService confirmations;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor sam() {
        return jwt().jwt(j -> j.subject("kc-sam").claim("name", "Sam Phiri")).authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
    }

    private static String reference() {
        return "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private ResultActions send(String json) throws Exception {
        return mvc.perform(post(CONFIRMATIONS).with(sam()).header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID mtn(UUID order, String reference, String gross) throws Exception {
        MvcResult made = send("{\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"reference\":\"" + reference + "\",\"gross\":" + gross + ",\"fee\":10.00}")
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(made.getResponse().getContentAsString(), "$.id"));
    }

    private ResultActions voidIt(UUID id, String reason) throws Exception {
        return mvc.perform(post(CONFIRMATIONS + "/" + id + "/void").with(sam()).contentType(MediaType.APPLICATION_JSON)
                .content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"));
    }

    private List<Map<String, Object>> events(UUID id) {
        return jdbc.queryForList("SELECT event_type, seq, actor, occurred_at, payload::text AS payload FROM outbox WHERE aggregate_type = 'payment_confirmation' "
                + "AND aggregate_id = ? ORDER BY seq", id.toString());
    }

    /** What order's answer does to a confirmation, done by hand: the consumer is tested on its own. */
    private void held(UUID id, UUID order) {
        jdbc.update("UPDATE payment_confirmation SET outcome = 'HELD', outcome_reason = 'WRONG_AMOUNT', outcome_at = now() WHERE id = ?", id);
        jdbc.update("INSERT INTO held_money (id, confirmation_id, order_id, reason, owed_gross, currency, status, created_at) "
                + "VALUES (?, ?, ?, 'WRONG_AMOUNT', 1000.00, 'ZMW', 'OWED', now())", UUID.randomUUID(), id, order);
    }

    // ---------------------------------------------------------------- voiding

    @Test
    @DisplayName("voiding a confirmation with a reason frees its reference (the corrected one can use it), leaves the money as recorded, and announces payment.voided")
    void voiding() throws Exception {
        UUID wrongOrder = UUID.randomUUID();
        String reference = reference();
        UUID id = mtn(wrongOrder, reference, "1000.00");

        voidIt(id, WHY).andExpect(status().isOk())
                .andExpect(jsonPath("$.voided").value(true))
                .andExpect(jsonPath("$.voidReason").value(WHY))
                .andExpect(jsonPath("$.voidedBy").value("user:kc-sam"))
                .andExpect(jsonPath("$.voidedAt").exists())
                .andExpect(jsonPath("$.gross").value(1000.00))
                .andExpect(jsonPath("$.outcome").value("PENDING"));

        assertThat(jdbc.queryForObject("SELECT reference_key FROM payment_confirmation WHERE id = ?", String.class, id)).as("the key is released").isNull();
        assertThat(jdbc.queryForObject("SELECT gross FROM payment_confirmation WHERE id = ?", java.math.BigDecimal.class, id)).as("the record is not edited").isEqualByComparingTo("1000.00");
        List<Map<String, Object>> events = events(id);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("payment.confirmed", "payment.voided");
        assertThat(events.get(1).get("actor")).isEqualTo("user:kc-sam");
        assertThat((String) events.get(1).get("payload")).contains("\"outcome\":\"PENDING\"").contains("\"reason\":\"" + WHY + "\"").contains("\"net\":990.00");
        Instant arrived = jdbc.queryForObject("SELECT arrived_at FROM payment_confirmation WHERE id = ?", java.sql.Timestamp.class, id).toInstant();
        assertThat((String) events.get(1).get("payload")).as("the day the money arrived, so finance reverses the fee in the month it posted").endsWith("\"arrivedAt\":\"" + arrived + "\"}");

        UUID corrected = mtn(UUID.randomUUID(), reference, "1000.00");
        assertThat(corrected).isNotEqualTo(id);
    }

    @Test
    @DisplayName("voiding money that is held for a customer closes that record too, and says it was HELD")
    void voidingHeldMoney() throws Exception {
        UUID order = UUID.randomUUID();
        UUID id = mtn(order, reference(), "1000.00");
        held(id, order);

        voidIt(id, WHY).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("HELD"));

        assertThat(jdbc.queryForObject("SELECT status FROM held_money WHERE confirmation_id = ?", String.class, id)).isEqualTo("VOIDED");
        assertThat((String) events(id).getLast().get("payload")).contains("\"outcome\":\"HELD\"");
    }

    @Test
    @DisplayName("a confirmation that paid a sale is never voided (409 confirmation-settled): it is the record the sale stands on, and correcting it is a later phase's")
    void aSettledConfirmationIsFinal() throws Exception {
        UUID id = mtn(UUID.randomUUID(), reference(), "1000.00");
        jdbc.update("UPDATE payment_confirmation SET outcome = 'SETTLED', outcome_at = now() WHERE id = ?", id);

        voidIt(id, WHY).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "confirmation-settled"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation_void WHERE confirmation_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT reference_key IS NOT NULL FROM payment_confirmation WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("voiding twice is 409 already-voided, and the second changes and announces nothing")
    void onlyOnce() throws Exception {
        UUID id = mtn(UUID.randomUUID(), reference(), "1000.00");
        voidIt(id, WHY).andExpect(status().isOk());

        voidIt(id, "a second reason, equally long").andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "already-voided"));

        assertThat(events(id)).hasSize(2);
    }

    @Test
    @DisplayName("a reason of at least ten characters is needed (spaces round it do not count), and an unknown confirmation is 404")
    void theReasonAndTheId() throws Exception {
        UUID id = mtn(UUID.randomUUID(), reference(), "1000.00");

        voidIt(id, null).andExpect(status().isBadRequest());
        voidIt(id, "too short").andExpect(status().isBadRequest());
        voidIt(id, "   short    ").andExpect(status().isBadRequest());
        voidIt(UUID.randomUUID(), WHY).andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "confirmation-not-found"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation_void WHERE confirmation_id = ?", Long.class, id)).isZero();
    }

    @Test
    @DisplayName("two people voiding the same confirmation at once: exactly one voids it, the other is told it is already voided, and it is announced once")
    void twoVoidsAtOnce() throws Exception {
        UUID id = mtn(UUID.randomUUID(), reference(), "1000.00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return voidIt(id, WHY).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(events(id)).extracting(e -> e.get("event_type")).containsExactly("payment.confirmed", "payment.voided");
    }

    @Test
    @DisplayName("only SALES_AGENT voids: a shopper and the payments admin without it are 403, an anonymous caller 401")
    void whoMayVoid() throws Exception {
        UUID id = mtn(UUID.randomUUID(), reference(), "1000.00");
        String url = CONFIRMATIONS + "/" + id + "/void";
        String json = "{\"reason\":\"" + WHY + "\"}";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- what has no outcome yet

    @Test
    @DisplayName("the pending list holds the confirmations order has not answered, oldest first, with their age and how often they were announced; settled, held and voided ones are not in it")
    void pending() throws Exception {
        UUID waiting = mtn(UUID.randomUUID(), reference(), "100.00");
        UUID settled = mtn(UUID.randomUUID(), reference(), "200.00");
        UUID heldOne = mtn(UUID.randomUUID(), reference(), "300.00");
        UUID voided = mtn(UUID.randomUUID(), reference(), "400.00");
        jdbc.update("UPDATE payment_confirmation SET outcome = 'SETTLED', outcome_at = now() WHERE id = ?", settled);
        held(heldOne, UUID.randomUUID());
        voidIt(voided, WHY).andExpect(status().isOk());

        mvc.perform(get(CONFIRMATIONS + "/pending").with(sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + waiting + "')].emitAttempts").value(1))
                .andExpect(jsonPath("$[?(@.id=='" + waiting + "')].stuck").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + waiting + "')].gross").value(100.00))
                .andExpect(jsonPath("$[?(@.id=='" + settled + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='" + heldOne + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='" + voided + "')]").isEmpty());
    }

    @Test
    @DisplayName("one that has waited longer than the settlement window (five minutes) is stuck: the list says so, with its age, so someone looks")
    void stuck() throws Exception {
        UUID waiting = mtn(UUID.randomUUID(), reference(), "100.00");

        var later = confirmations.pending(Instant.now().plus(Duration.ofMinutes(6)));
        var soon = confirmations.pending(Instant.now().plus(Duration.ofMinutes(4)));

        assertThat(later).filteredOn(p -> p.id().equals(waiting)).singleElement().satisfies(p -> {
            assertThat(p.stuck()).isTrue();
            assertThat(p.ageSeconds()).isBetween(350L, 380L);
        });
        assertThat(soon).filteredOn(p -> p.id().equals(waiting)).singleElement().satisfies(p -> assertThat(p.stuck()).isFalse());
    }

    @Test
    @DisplayName("the pending list is SALES_AGENT's too")
    void whoMayListPending() throws Exception {
        mvc.perform(get(CONFIRMATIONS + "/pending")).andExpect(status().isUnauthorized());
        mvc.perform(get(CONFIRMATIONS + "/pending").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isForbidden());
    }
}
