package com.oriah.payment.confirmation;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff confirming money that reached Oriah (Phase 7): the only road by which an order becomes paid. A confirmation is a record of method,
 * reference, gross, fee and the DATE THE MONEY ARRIVED (not the day it was keyed), with who confirmed it; net is worked out and never accepted;
 * a reference confirms money once; money older than 72 hours is the owner's to record, with a reason. Confirming announces
 * {@code payment.confirmed} on the event log for order to act on.
 *
 * <p>Rows are never deleted (append-only), so every test makes its own references and its own order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConfirmationHttpTest {

    private static final String CONFIRMATIONS = "/api/v1/admin/payments/confirmations";
    private static final String PROBLEMS = "https://oriah.dev/problems/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor sam() {
        return jwt().jwt(j -> j.subject("kc-sam").claim("name", "Sam Phiri")).authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
    }

    private static RequestPostProcessor owner() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("name", "Maya Banda"))
                .authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE"));
    }

    private static String reference() {
        return "mp " + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String body(UUID order, String method, String reference, String gross, String fee, Instant arrived) {
        return "{\"orderId\":\"" + order + "\",\"method\":\"" + method + "\"" + (reference == null ? "" : ",\"reference\":\"" + reference + "\"")
                + ",\"gross\":" + gross + (fee == null ? "" : ",\"fee\":" + fee) + (arrived == null ? "" : ",\"arrivedAt\":\"" + arrived + "\"") + "}";
    }

    private ResultActions confirm(RequestPostProcessor who, String key, String json) throws Exception {
        return mvc.perform(post(CONFIRMATIONS).with(who).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions confirm(String json) throws Exception {
        return confirm(sam(), "key-" + UUID.randomUUID(), json);
    }

    private static Instant ago(Duration age) {
        return Instant.now().minus(age).truncatedTo(ChronoUnit.MILLIS);
    }

    private long rows(String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE " + where, Long.class, args);
    }

    private List<Map<String, Object>> events(String confirmationId) {
        return jdbc.queryForList("SELECT event_type, seq, schema_version, topic, actor, occurred_at, payload::text AS payload FROM outbox "
                + "WHERE aggregate_type = 'payment_confirmation' AND aggregate_id = ? ORDER BY seq", confirmationId);
    }

    private static String idOf(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    // ---------------------------------------------------------------- recording it

    @Test
    @DisplayName("confirming money records it as keyed (reference in its one spelling, net worked out, who confirmed it, PENDING until order answers) and announces payment.confirmed dated to the arrival")
    void confirming() throws Exception {
        UUID order = UUID.randomUUID();
        Instant arrived = ago(Duration.ofHours(5));

        MvcResult made = confirm(body(order, "mtn", "mp 2411 abc " + UUID.randomUUID().toString().substring(0, 6), "1000.00", "10.00", arrived))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.method").value("MTN"))
                .andExpect(jsonPath("$.gross").value(1000.00))
                .andExpect(jsonPath("$.fee").value(10.00))
                .andExpect(jsonPath("$.net").value(990.00))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.arrivedAt").value(arrived.toString()))
                .andExpect(jsonPath("$.backdateReason").value(nullValue()))
                .andExpect(jsonPath("$.confirmedBy").value("user:kc-sam"))
                .andExpect(jsonPath("$.confirmedByName").value("Sam Phiri"))
                .andExpect(jsonPath("$.outcome").value("PENDING"))
                .andExpect(jsonPath("$.outcomeReason").value(nullValue()))
                .andExpect(jsonPath("$.voided").value(false))
                .andReturn();
        String id = idOf(made);
        String reference = com.jayway.jsonpath.JsonPath.read(made.getResponse().getContentAsString(), "$.reference");

        assertThat(reference).startsWith("MP2411ABC").doesNotContain(" ");
        assertThat(jdbc.queryForObject("SELECT reference_key FROM payment_confirmation WHERE id = ?::uuid", String.class, id)).isEqualTo("MTN:" + reference);
        assertThat(events(id)).hasSize(1).first().satisfies(event -> {
            assertThat(event.get("event_type")).isEqualTo("payment.confirmed");
            assertThat(event.get("seq")).isEqualTo(1L);
            assertThat(event.get("schema_version")).isEqualTo(1);
            assertThat(event.get("topic")).isEqualTo("oriah.payment");
            assertThat(event.get("actor")).isEqualTo("user:kc-sam");
            assertThat(((java.sql.Timestamp) event.get("occurred_at")).toInstant()).as("dated to the arrival, not to the keying").isEqualTo(arrived);
            assertThat((String) event.get("payload")).contains("\"confirmationId\":\"" + id + "\"").contains("\"orderId\":\"" + order + "\"")
                    .contains("\"gross\":1000.00").contains("\"fee\":10.00").contains("\"net\":990.00").contains("\"attempt\":1");
        });
    }

    @Test
    @DisplayName("cash needs no reference and has no key, so two cash payments of the same amount for the same order are two payments")
    void cash() throws Exception {
        UUID order = UUID.randomUUID();

        MvcResult first = confirm(body(order, "CASH", null, "250.00", null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(nullValue())).andExpect(jsonPath("$.fee").value(0.00)).andExpect(jsonPath("$.net").value(250.00)).andReturn();
        MvcResult second = confirm(body(order, "CASH", null, "250.00", null, null)).andExpect(status().isCreated()).andReturn();

        assertThat(idOf(first)).isNotEqualTo(idOf(second));
        assertThat(jdbc.queryForObject("SELECT reference_key FROM payment_confirmation WHERE id = ?::uuid", String.class, idOf(first))).isNull();
    }

    @Test
    @DisplayName("an arrival not given is now, and a fee not given is nothing")
    void defaults() throws Exception {
        Instant before = Instant.now().minusSeconds(2);

        MvcResult made = confirm(body(UUID.randomUUID(), "CASH", null, "100.00", null, null)).andExpect(status().isCreated()).andReturn();

        String arrived = com.jayway.jsonpath.JsonPath.read(made.getResponse().getContentAsString(), "$.arrivedAt");
        assertThat(Instant.parse(arrived)).isBetween(before, Instant.now().plusSeconds(2));
    }

    // ---------------------------------------------------------------- once

    @Test
    @DisplayName("the same request sent again (a double click, a lost reply) with the same Idempotency-Key is the same confirmation, answered 200: one row, one announcement")
    void aRepeatIsTheSameConfirmation() throws Exception {
        String key = "key-" + UUID.randomUUID();
        String json = body(UUID.randomUUID(), "airtel", reference(), "300.00", "3.00", ago(Duration.ofHours(1)));

        MvcResult first = confirm(sam(), key, json).andExpect(status().isCreated()).andReturn();
        MvcResult again = confirm(sam(), key, json).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(idOf(first))).andReturn();

        assertThat(idOf(again)).isEqualTo(idOf(first));
        assertThat(rows("id = ?::uuid", idOf(first))).isEqualTo(1);
        assertThat(events(idOf(first))).hasSize(1);
    }

    @Test
    @DisplayName("the same Idempotency-Key with a DIFFERENT request is refused (409 idempotency-key-reused), not answered as the first: nothing new is recorded")
    void aKeyIsNotReusedForSomethingElse() throws Exception {
        String key = "key-" + UUID.randomUUID();
        UUID order = UUID.randomUUID();
        Instant arrived = ago(Duration.ofHours(1));
        confirm(sam(), key, body(order, "CASH", null, "100.00", null, arrived)).andExpect(status().isCreated());

        confirm(sam(), key, body(order, "CASH", null, "900.00", null, arrived)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "idempotency-key-reused"));

        assertThat(rows("order_id = ?", order)).isEqualTo(1);
    }

    @Test
    @DisplayName("a request with no Idempotency-Key is 400: a person's double click must never record money twice")
    void aKeyIsRequired() throws Exception {
        mvc.perform(post(CONFIRMATIONS).with(sam()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "CASH", null, "100.00", null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a reference confirms money once, however it is spelled and for whichever order: the second is 409 reference-already-used naming the first, and nothing is recorded or announced")
    void aReferenceIsUsedOnce() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        MvcResult first = confirm(body(UUID.randomUUID(), "MTN", "MP " + unique.toLowerCase(), "500.00", "5.00", null)).andExpect(status().isCreated()).andReturn();
        UUID otherOrder = UUID.randomUUID();

        confirm(body(otherOrder, "mtn", " mp" + unique + " ", "500.00", "5.00", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "reference-already-used"))
                .andExpect(jsonPath("$.confirmationId").value(idOf(first)));

        assertThat(rows("order_id = ?", otherOrder)).isZero();
        confirm(body(otherOrder, "AIRTEL", "MP" + unique, "500.00", "5.00", null)).andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("net is worked out (gross less fee) and never accepted: a request that carries it is 400 on that field")
    void netIsNeverAccepted() throws Exception {
        confirm("{\"orderId\":\"" + UUID.randomUUID() + "\",\"method\":\"CASH\",\"gross\":100.00,\"fee\":1.00,\"net\":99.00}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field", hasItem("net")));
    }

    @Test
    @DisplayName("a confirmation that makes no sense is 400 and nothing is recorded: no order, no or unknown method, no reference for a non-cash payment, a reference with odd characters, an amount that is zero, finer than a ngwee or less than its fee")
    void validation() throws Exception {
        UUID order = UUID.randomUUID();
        long before = rows("true");

        for (String bad : new String[] {
                "{\"method\":\"CASH\",\"gross\":100.00}",
                body(order, "BITCOIN", reference(), "100.00", null, null),
                "{\"orderId\":\"" + order + "\",\"gross\":100.00}",
                body(order, "MTN", null, "100.00", null, null),
                body(order, "MTN", "MP#12345", "100.00", null, null),
                body(order, "MTN", "ab", "100.00", null, null),
                body(order, "CASH", null, "0.00", null, null),
                body(order, "CASH", null, "-5.00", null, null),
                body(order, "CASH", null, "100.005", null, null),
                body(order, "CASH", null, "100.00", "100.01", null),
                body(order, "CASH", null, "100.00", "-0.01", null),
                body(order, "CASH", null, "100.00", "0.005", null),
                "{\"orderId\":\"" + order + "\",\"method\":\"CASH\"}",
                body(order, "CASH", null, "100.00", null, Instant.now().plus(Duration.ofHours(2)))}) {
            confirm(bad).andExpect(status().isBadRequest());
        }
        assertThat(rows("true")).isEqualTo(before);
    }

    @Test
    @DisplayName("prices are in Kwacha: another currency is 400 unsupported-currency, and ZMW named is fine")
    void onlyTheShopsCurrency() throws Exception {
        UUID order = UUID.randomUUID();

        confirm("{\"orderId\":\"" + order + "\",\"method\":\"CASH\",\"gross\":100.00,\"currency\":\"USD\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "unsupported-currency"));
        confirm("{\"orderId\":\"" + order + "\",\"method\":\"CASH\",\"gross\":100.00,\"currency\":\"ZMW\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value("ZMW"));
    }

    // ---------------------------------------------------------------- the 72 hours

    @Test
    @DisplayName("someone who may confirm but not back-date cannot record money older than 72 hours: 403, and nothing is recorded or announced; within 72 hours is fine")
    void salesCannotBackdate() throws Exception {
        UUID order = UUID.randomUUID();

        confirm(sam(), "key-" + UUID.randomUUID(), body(order, "CASH", null, "100.00", null, ago(Duration.ofHours(73)))).andExpect(status().isForbidden());
        confirm(sam(), "key-" + UUID.randomUUID(), body(order, "CASH", null, "100.00", null, ago(Duration.ofHours(72).minusMinutes(5)))).andExpect(status().isCreated());

        assertThat(rows("order_id = ?", order)).isEqualTo(1);
    }

    @Test
    @DisplayName("the owner (BACKDATE_SALE) records older money with a reason: it is kept dated to the arrival, with the reason; without a reason, or a short one, it is 400 and nothing is recorded")
    void theOwnerBackdates() throws Exception {
        UUID order = UUID.randomUUID();
        Instant arrived = ago(Duration.ofDays(5));

        confirm(owner(), "key-" + UUID.randomUUID(), body(order, "CASH", null, "100.00", null, arrived)).andExpect(status().isBadRequest());
        confirm(owner(), "key-" + UUID.randomUUID(), body(order, "CASH", null, "100.00", null, arrived).replace("}", ",\"backdateReason\":\"abcd\"}")).andExpect(status().isBadRequest());
        assertThat(rows("order_id = ?", order)).isZero();

        MvcResult made = confirm(owner(), "key-" + UUID.randomUUID(),
                body(order, "CASH", null, "100.00", null, arrived).replace("}", ",\"backdateReason\":\"  customer paid on Friday, I was away  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.arrivedAt").value(arrived.toString()))
                .andExpect(jsonPath("$.backdateReason").value("customer paid on Friday, I was away"))
                .andExpect(jsonPath("$.confirmedBy").value("user:kc-maya"))
                .andReturn();
        assertThat(((java.sql.Timestamp) events(idOf(made)).getFirst().get("occurred_at")).toInstant()).isEqualTo(arrived);
    }

    // ---------------------------------------------------------------- who may

    @Test
    @DisplayName("only SALES_AGENT confirms money: an anonymous caller is 401, a shopper and the payments admin without it are 403")
    void whoMay() throws Exception {
        String json = body(UUID.randomUUID(), "CASH", null, "100.00", null, null);

        mvc.perform(post(CONFIRMATIONS).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        confirm(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")), "k-" + UUID.randomUUID(), json).andExpect(status().isForbidden());
        confirm(jwt().authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN")), "k-" + UUID.randomUUID(), json).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- reading one

    @Test
    @DisplayName("a confirmation can be read back by its id, and an unknown id is 404 confirmation-not-found")
    void readingOne() throws Exception {
        MvcResult made = confirm(body(UUID.randomUUID(), "CASH", null, "100.00", null, null)).andExpect(status().isCreated()).andReturn();

        mvc.perform(get(CONFIRMATIONS + "/" + idOf(made)).with(sam())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(idOf(made)))
                .andExpect(jsonPath("$.outcome").value("PENDING"));
        mvc.perform(get(CONFIRMATIONS + "/" + UUID.randomUUID()).with(sam())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "confirmation-not-found"));
    }
}
