package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.provider.PaymentProvider.Paid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ZynlePay's "payment done" message (its callback) carries no signature: anyone who knows the address can send one. So it proves nothing, and it
 * is treated as exactly that (Phase 13): <b>a callback never changes what a payment is.</b> The most it does is bring forward the moment Oriah
 * asks ZynlePay itself; what Oriah then does rests on ZynlePay's answer to that question alone.
 *
 * <p>The door is open to the world (ZynlePay sends no token), so it gives nothing away and cannot be made to work hard: every answer is the same
 * "received" whatever was sent, a body is read only up to a small size, and an attempt is asked about at most once every ten seconds however many
 * messages name it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScriptedProvider.Config.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CallbackIsOnlyANudgeTest {

    private static final String CALLBACK = "/api/v1/payments/zynlepay/callback";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ProviderPaymentChecks checks;
    @Autowired
    private ScriptedProvider zynlePay;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private OrderClient orders;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Instant sent;

    @BeforeEach
    void nothingElseIsDue() {
        jdbc.update("UPDATE provider_payment SET next_check_at = NULL, final_recheck_at = NULL WHERE next_check_at IS NOT NULL OR final_recheck_at IS NOT NULL");
        sent = Instant.now().minusSeconds(60);
    }

    /** A payment in flight, next to be asked about ten minutes from now. */
    private UUID pending() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, fee_percent,
                                              fee_fixed, status, idempotency_key, request_fingerprint, started_at, sent_at, next_check_at, provider_code)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', 180.00, 'ZMW', 2.5, 0.00, 'PENDING', ?, 'fp', ?, ?, ?, '120')""",
                id, ProviderReference.mint(), UUID.randomUUID(), "key-" + id, Timestamp.from(sent), Timestamp.from(sent),
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))));
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM provider_payment WHERE id = ?", id);
    }

    private String reference(UUID id) {
        return (String) row(id).get("reference_no");
    }

    private static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private MvcResult callback(String body) throws Exception {
        return mvc.perform(post(CALLBACK).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static void received(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"received\":true}");
    }

    private long confirmations(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE provider_payment_id = ?", Long.class, id);
    }

    @Test
    @DisplayName("a message saying 'paid' changes nothing: the payment is still pending and no money is recorded; it only makes Oriah ask ZynlePay sooner")
    void aCallbackSayingPaidChangesNothingUntilOurCheckSaysSo() throws Exception {
        UUID id = pending();

        received(callback("{\"reference_no\":\"" + reference(id) + "\",\"response_code\":\"100\",\"amount\":\"180.00\"}"));

        assertThat(row(id).get("status")).isEqualTo("PENDING");
        assertThat(confirmations(id)).isZero();
        assertThat(row(id).get("callback_count")).isEqualTo(1);
        assertThat(row(id).get("last_callback_at")).isNotNull();
        assertThat(at(row(id).get("next_check_at"))).as("brought forward to now").isBeforeOrEqualTo(Instant.now());

        // Asked, ZynlePay itself says it is still deciding: whatever the message claimed, nothing is recorded.
        assertThat(checks.poll(Instant.now())).isEqualTo(1);
        assertThat(row(id).get("status")).isEqualTo("PENDING");
        assertThat(confirmations(id)).isZero();

        // Only ZynlePay's own answer to Oriah's own question makes it money.
        zynlePay.statusOf(reference(id), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));
        received(callback("{\"reference_no\":\"" + reference(id) + "\",\"response_code\":\"100\"}"));
        checks.poll(Instant.now().plusSeconds(11));
        assertThat(row(id).get("status")).isEqualTo("VERIFIED");
        assertThat(confirmations(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("every answer is the same 'received': a reference nobody knows, a body that is not readable, nothing at all, and nothing is written for any of them")
    void theDoorGivesNothingAway() throws Exception {
        long before = jdbc.queryForObject("SELECT COALESCE(sum(callback_count), 0) FROM provider_payment", Long.class);

        received(callback("{\"reference_no\":\"OZNOBODYNOBODY00\",\"response_code\":\"100\"}"));
        received(callback("this is not json"));
        received(callback(""));
        received(callback("{\"reference_no\":{\"$ne\":null},\"response_code\":\"100\"}"));
        received(callback("{\"reference_no\":\"'; DROP TABLE provider_payment; --\",\"response_code\":\"100\"}"));
        received(callback("[1,2,3]"));

        assertThat(jdbc.queryForObject("SELECT COALESCE(sum(callback_count), 0) FROM provider_payment", Long.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("the message is read however it is shaped: wrapped in 'response', as a form, with a token nobody asked for; and never past its size limit")
    void howeverItIsShaped() throws Exception {
        UUID wrapped = pending();
        received(callback("{\"response\":{\"reference_no\":\"" + reference(wrapped) + "\",\"response_code\":\"100\"}}"));
        assertThat(row(wrapped).get("callback_count")).isEqualTo(1);

        UUID form = pending();
        received(mvc.perform(post(CALLBACK).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("response_code=100&reference_no=" + reference(form))).andReturn());
        assertThat(row(form).get("callback_count")).isEqualTo(1);

        // A stray Authorization header must not turn ZynlePay's message into a 401.
        UUID withToken = pending();
        received(mvc.perform(post(CALLBACK).header("Authorization", "Bearer not-a-token-of-ours").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference_no\":\"" + reference(withToken) + "\",\"response_code\":\"100\"}")).andReturn());
        assertThat(row(withToken).get("callback_count")).isEqualTo(1);

        UUID big = pending();
        MvcResult tooLarge = callback("{\"reference_no\":\"" + reference(big) + "\",\"padding\":\"" + "x".repeat(8_200) + "\"}");
        assertThat(tooLarge.getResponse().getStatus()).isEqualTo(413);
        assertThat(tooLarge.getResponse().getContentAsString()).contains("payload-too-large");
        assertThat(row(big).get("callback_count")).isEqualTo(0);
    }

    @Test
    @DisplayName("a flood of messages for one payment makes ZynlePay be asked at most once every ten seconds, and never puts a check off")
    void aFloodAsksOnce() throws Exception {
        UUID id = pending();
        String body = "{\"reference_no\":\"" + reference(id) + "\",\"response_code\":\"100\"}";

        received(callback(body));
        Instant asked = Instant.now();
        checks.poll(asked);
        for (int i = 0; i < 20; i++) {
            received(callback(body));
        }

        assertThat(row(id).get("callback_count")).isEqualTo(21);
        assertThat(at(row(id).get("next_check_at"))).as("not before ten seconds after it was last asked about")
                .isAfterOrEqualTo(at(row(id).get("last_checked_at")).plusSeconds(10)).isBefore(asked.plusSeconds(61));
        // Judged at a fixed moment, five seconds after it was last asked about (the messages themselves can take long on a slow machine).
        assertThat(checks.poll(at(row(id).get("last_checked_at")).plusSeconds(5))).isZero();
        assertThat(zynlePay.statusCalls(reference(id))).isEqualTo(1);

        // And a check that was due sooner than the message would make it is left where it was.
        UUID soon = pending();
        Instant already = Instant.now().minusSeconds(30);
        jdbc.update("UPDATE provider_payment SET next_check_at = ? WHERE id = ?", Timestamp.from(already), soon);
        received(callback("{\"reference_no\":\"" + reference(soon) + "\",\"response_code\":\"100\"}"));
        assertThat(at(row(soon).get("next_check_at"))).isEqualTo(already.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("a declined payment can still be asked about on a message (ZynlePay may contradict itself); one that is recorded, was never sent, or the owner settled is left alone")
    void whichPaymentsAMessageCanTouch() throws Exception {
        UUID declined = pending();
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'DECLINED', holds_order = false, failed_at = now(), next_check_at = NULL "
                + "WHERE id = ?", declined);
        received(callback("{\"reference_no\":\"" + reference(declined) + "\",\"response_code\":\"100\"}"));
        assertThat(row(declined).get("next_check_at")).isNotNull();
        // Asked, and ZynlePay says nothing new: it is not asked about again and again.
        checks.poll(Instant.now().plusSeconds(1));
        assertThat(row(declined).get("status")).isEqualTo("FAILED");
        assertThat(row(declined).get("next_check_at")).isNull();

        UUID neverSent = pending();
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'NOT_SENT', holds_order = false, failed_at = now(), next_check_at = NULL "
                + "WHERE id = ?", neverSent);
        UUID notPaid = pending();
        jdbc.update("UPDATE provider_payment SET status = 'UNRESOLVED', unresolved_reason = 'NO_ANSWER', next_check_at = NULL WHERE id = ?", notPaid);
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', unresolved_reason = NULL, failure_code = 'MARKED_NOT_PAID', holds_order = false, "
                + "failed_at = now() WHERE id = ?", notPaid);
        UUID longAgo = pending();
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'DECLINED', holds_order = false, failed_at = now() - interval '15 days', "
                + "next_check_at = NULL WHERE id = ?", longAgo);
        for (UUID id : new UUID[] {neverSent, notPaid, longAgo}) {
            received(callback("{\"reference_no\":\"" + reference(id) + "\",\"response_code\":\"100\"}"));
            assertThat(row(id).get("next_check_at")).as(row(id).get("failure_code") + "").isNull();
            assertThat(row(id).get("callback_count")).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("only that one door, and only POST, is open: reading it, or any other payment door, without a token is still 401")
    void onlyThatDoorIsOpen() throws Exception {
        assertThat(mvc.perform(get(CALLBACK)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/payments/zynlepay/other").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse()
                .getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/payments/provider-payments").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse()
                .getStatus()).isEqualTo(401);
        // As the real decoder does with a token that is not one of ours.
        org.mockito.Mockito.when(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("not a token of ours"));
        assertThat(mvc.perform(get("/api/v1/payments/contact-info").header("Authorization", "Bearer not-a-token-of-ours")).andReturn().getResponse()
                .getStatus()).as("a bad token is still a bad token everywhere else").isEqualTo(401);
    }
}
