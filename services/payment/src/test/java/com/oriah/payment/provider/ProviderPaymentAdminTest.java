package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.provider.PaymentProvider.Failed;
import com.oriah.payment.provider.PaymentProvider.NoAnswer;
import com.oriah.payment.provider.PaymentProvider.Paid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What staff and the owner do about payments through ZynlePay (Phase 13).
 *
 * <p><b>Staff (SALES_AGENT)</b> see every payment and where it stands, so nobody chases a customer for money that is on its way, and may press
 * "check now", which asks ZynlePay exactly as the checker does. They decide nothing.
 *
 * <p><b>The owner (PAYMENT_ADMIN)</b> settles the payments ZynlePay did not: paid or not paid, from ZynlePay's own records, with what was looked
 * at written down. ZynlePay is asked once more first, and its answer still counts for more than a person's word: "paid" from ZynlePay is recorded
 * as ZynlePay's, "failed" refuses the owner's "paid", and "still deciding" refuses "not paid" unless the owner insists. The evidence is kept with
 * the decision and travels nowhere else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScriptedProvider.Config.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProviderPaymentAdminTest {

    private static final String BASE = "/api/v1/admin/payments/provider-payments";
    private static final String EVIDENCE = "ZynlePay dashboard, transaction 884213, shows it settled on 17 November";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ScriptedProvider zynlePay;
    @Autowired
    private ProviderPaymentChecks checks;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    @SuppressWarnings("unused")
    private OrderClient orders;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private Instant sent;

    @BeforeEach
    void nothingElseIsDue() {
        jdbc.update("UPDATE provider_payment SET next_check_at = NULL, final_recheck_at = NULL WHERE next_check_at IS NOT NULL OR final_recheck_at IS NOT NULL");
        sent = Instant.now().minus(Duration.ofMinutes(40)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    // ---------------------------------------------------------------- who

    private static RequestPostProcessor sara() {
        return jwt().jwt(j -> j.subject("kc-sara").claim("name", "Sara Zulu")).authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
    }

    private static RequestPostProcessor olive() {
        return jwt().jwt(j -> j.subject("kc-olive").claim("name", "Olive Mwale")).authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"),
                new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
    }

    private static RequestPostProcessor oliveWhoMayBackdate() {
        return jwt().jwt(j -> j.subject("kc-olive").claim("name", "Olive Mwale")).authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"),
                new SimpleGrantedAuthority("ROLE_SALES_AGENT"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE"));
    }

    // ---------------------------------------------------------------- fixtures

    /** An MTN payment of K180.00 sent forty minutes ago and still in flight. */
    private UUID pending() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, fee_percent,
                                              fee_fixed, status, idempotency_key, request_fingerprint, started_at, sent_at, provider_code)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', 180.00, 'ZMW', 2.5, 0.00, 'PENDING', ?, 'fp', ?, ?, '120')""",
                id, ProviderReference.mint(), UUID.randomUUID(), "key-" + id, Timestamp.from(sent), Timestamp.from(sent));
        return id;
    }

    /** One ZynlePay has not answered for half an hour: the owner's. */
    private UUID unanswered() {
        UUID id = pending();
        jdbc.update("UPDATE provider_payment SET status = 'UNRESOLVED', unresolved_reason = 'NO_ANSWER', unresolved_at = now() WHERE id = ?", id);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM provider_payment WHERE id = ?", id);
    }

    private String reference(UUID id) {
        return (String) row(id).get("reference_no");
    }

    private List<Map<String, Object>> confirmations(UUID attempt) {
        return jdbc.queryForList("SELECT * FROM payment_confirmation WHERE provider_payment_id = ? ORDER BY confirmed_at", attempt);
    }

    private List<String> updates(UUID attempt) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_type = 'provider_payment' AND aggregate_id = ? ORDER BY seq", String.class,
                attempt.toString());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String problem(MvcResult result) throws Exception {
        String type = body(result).get("type").asString();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private MvcResult resolve(RequestPostProcessor who, UUID id, String key, String body) throws Exception {
        return mvc.perform(post(BASE + "/" + id + "/resolve").with(who).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private static String decision(String outcome, String more) {
        return "{\"outcome\":\"" + outcome + "\",\"evidence\":\"" + EVIDENCE + "\"" + (more == null ? "" : "," + more) + "}";
    }

    // ---------------------------------------------------------------- staff: seeing and checking

    @Test
    @DisplayName("staff see the payments in flight with the payer's number masked; the owner sees it whole (to send a refund back to it); a shopper sees none of it")
    void whoSeesWhat() throws Exception {
        UUID id = pending();
        UUID order = (UUID) row(id).get("order_id");

        MvcResult staff = mvc.perform(get(BASE).param("orderId", order.toString()).with(sara())).andReturn();
        assertThat(staff.getResponse().getStatus()).isEqualTo(200);
        JsonNode seen = body(staff).get(0);
        assertThat(seen.get("id").asString()).isEqualTo(id.toString());
        assertThat(seen.get("status").asString()).isEqualTo("PENDING");
        assertThat(seen.get("method").asString()).isEqualTo("MTN");
        assertThat(new BigDecimal(seen.get("amount").asString())).isEqualByComparingTo("180.00");
        assertThat(seen.get("referenceNo").asString()).isEqualTo(reference(id));
        assertThat(seen.get("payerPhone").asString()).isEqualTo("+2609*****567");
        assertThat(seen.get("ageSeconds").asLong()).isBetween(2_390L, 2_500L);
        assertThat(staff.getResponse().getContentAsString()).doesNotContain("260961234567");

        MvcResult owner = mvc.perform(get(BASE).param("orderId", order.toString()).with(olive())).andReturn();
        assertThat(body(owner).get(0).get("payerPhone").asString()).isEqualTo("+260961234567");

        assertThat(mvc.perform(get(BASE).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(BASE)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(BASE).param("status", "MAYBE").with(sara())).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("the list is what is open by default (oldest first); the others are asked for by name; one payment shows every time ZynlePay was asked about it")
    void theListAndOnePayment() throws Exception {
        UUID open = unanswered();
        UUID done = pending();
        zynlePay.statusOf(reference(done), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));
        checks.checkOne(done, Instant.now(), "STAFF", "user:kc-sara");

        String listed = mvc.perform(get(BASE).with(sara())).andReturn().getResponse().getContentAsString();
        assertThat(listed).contains(open.toString()).doesNotContain(done.toString());
        assertThat(mvc.perform(get(BASE).param("status", "VERIFIED").with(sara())).andReturn().getResponse().getContentAsString())
                .contains(done.toString()).doesNotContain(open.toString());
        assertThat(mvc.perform(get(BASE).param("status", "UNRESOLVED").with(sara())).andReturn().getResponse().getContentAsString()).contains(open.toString());

        MvcResult one = mvc.perform(get(BASE + "/" + done).with(sara())).andReturn();
        assertThat(one.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(one).get("status").asString()).isEqualTo("VERIFIED");
        assertThat(body(one).get("confirmationId").asString()).isEqualTo(row(done).get("confirmation_id").toString());
        assertThat(new BigDecimal(body(one).get("fee").asString())).isEqualByComparingTo("4.50");
        assertThat(body(one).get("checks")).hasSize(1);
        assertThat(body(one).get("checks").get(0).get("outcome").asString()).isEqualTo("PAID");
        assertThat(body(one).get("checks").get(0).get("source").asString()).isEqualTo("STAFF");
        MvcResult none = mvc.perform(get(BASE + "/" + UUID.randomUUID()).with(sara())).andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(404);
        assertThat(problem(none)).isEqualTo("provider-payment-not-found");
    }

    @Test
    @DisplayName("'check now' asks ZynlePay as the checker does and shows the result; a payment being asked about just now is not asked twice; one that is over cannot be checked")
    void checkNow() throws Exception {
        UUID id = pending();
        zynlePay.statusOf(reference(id), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));

        MvcResult checked = mvc.perform(post(BASE + "/" + id + "/check").with(sara())).andReturn();
        assertThat(checked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(checked).get("status").asString()).isEqualTo("VERIFIED");
        assertThat(confirmations(id)).singleElement().satisfies(money -> assertThat(money.get("confirmed_by")).as("ZynlePay's answer, not Sara's word")
                .isEqualTo("system:zynlepay"));
        assertThat(jdbc.queryForObject("SELECT asked_by FROM provider_payment_check WHERE payment_id = ?", String.class, id)).isEqualTo("user:kc-sara");

        MvcResult over = mvc.perform(post(BASE + "/" + id + "/check").with(sara())).andReturn();
        assertThat(over.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(over)).isEqualTo("provider-payment-final");

        UUID busy = pending();
        jdbc.update("UPDATE provider_payment SET check_lease_until = now() + interval '1 minute' WHERE id = ?", busy);
        MvcResult justNow = mvc.perform(post(BASE + "/" + busy + "/check").with(sara())).andReturn();
        assertThat(justNow.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(justNow).get("checkedJustNow").asBoolean()).isTrue();
        assertThat(zynlePay.statusCalls(reference(busy))).isZero();

        assertThat(mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/check").with(sara())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post(BASE + "/" + busy + "/check").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- the owner decides

    @Test
    @DisplayName("the owner marks an unanswered payment paid: K180.00 is recorded under the owner's name, dated as told, and the evidence stays with the decision and nowhere else")
    void theOwnerMarksItPaid() throws Exception {
        UUID id = unanswered();
        String key = "res-" + UUID.randomUUID();

        assertThat(resolve(sara(), id, key, decision("PAID", null)).getResponse().getStatus()).as("staff decide nothing").isEqualTo(403);
        assertThat(confirmations(id)).isEmpty();

        MvcResult resolved = resolve(olive(), id, key, decision("PAID", null));

        assertThat(resolved.getResponse().getStatus()).as(resolved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(resolved).get("status").asString()).isEqualTo("VERIFIED");
        assertThat(body(resolved).get("verifiedByCheck").asBoolean()).isFalse();
        assertThat(row(id).get("holds_order")).isEqualTo(false);
        assertThat(confirmations(id)).singleElement().satisfies(money -> {
            assertThat(money.get("confirmed_by")).isEqualTo("user:kc-olive");
            assertThat(money.get("confirmed_by_name")).isEqualTo("Olive Mwale");
            assertThat((BigDecimal) money.get("gross")).isEqualByComparingTo("180.00");
            assertThat((BigDecimal) money.get("fee")).isEqualByComparingTo("4.50");
            assertThat(money.get("provider")).isEqualTo("ZYNLEPAY");
            assertThat(money.get("backdate_reason")).as("never the evidence: this text is published").isNull();
        });
        assertThat(jdbc.queryForMap("SELECT * FROM provider_payment_resolution WHERE payment_id = ?", id)).satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("PAID");
            assertThat(decision.get("evidence")).isEqualTo(EVIDENCE);
            assertThat(decision.get("decided_by")).isEqualTo("user:kc-olive");
            assertThat(decision.get("check_outcome")).isEqualTo("PENDING");
            assertThat(decision.get("resulting_status")).isEqualTo("VERIFIED");
        });
        // The evidence is in no event of any kind.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload::text LIKE '%884213, shows it settled%'", Long.class)).isZero();
        assertThat(updates(id)).last().satisfies(payload -> assertThat(payload).contains("\"status\":\"VERIFIED\""));

        // The same decision sent again is the same answer; the key with another decision is refused.
        assertThat(resolve(olive(), id, key, decision("PAID", null)).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirmations(id)).hasSize(1);
        MvcResult other = resolve(olive(), id, key, decision("NOT_PAID", null));
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(other)).isEqualTo("idempotency-key-reused");
    }

    @Test
    @DisplayName("ZynlePay is asked once more first, and its answer counts for more than a person's: its 'paid' is recorded as ZynlePay's, its 'failed' refuses the owner's 'paid'")
    void zynlePaysAnswerComesFirst() throws Exception {
        UUID paid = unanswered();
        zynlePay.statusOf(reference(paid), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));
        MvcResult byCheck = resolve(olive(), paid, "res-" + UUID.randomUUID(), decision("PAID", null));
        assertThat(byCheck.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(byCheck).get("verifiedByCheck").asBoolean()).isTrue();
        String sameKey = "res-" + UUID.randomUUID();
        UUID again = unanswered();
        zynlePay.statusOf(reference(again), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));
        assertThat(body(resolve(olive(), again, sameKey, decision("PAID", null))).get("verifiedByCheck").asBoolean()).isTrue();
        assertThat(body(resolve(olive(), again, sameKey, decision("PAID", null))).get("verifiedByCheck").asBoolean())
                .as("the same decision sent again is the same answer").isTrue();
        assertThat(confirmations(paid)).singleElement().satisfies(money -> assertThat(money.get("confirmed_by")).isEqualTo("system:zynlepay"));

        UUID failed = unanswered();
        zynlePay.statusOf(reference(failed), new Failed("995"));
        MvcResult refused = resolve(olive(), failed, "res-" + UUID.randomUUID(), decision("PAID", null));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(refused)).isEqualTo("provider-says-failed");
        assertThat(row(failed).get("status")).isEqualTo("FAILED");
        assertThat(row(failed).get("failure_code")).isEqualTo("DECLINED");
        assertThat(confirmations(failed)).isEmpty();
    }

    @Test
    @DisplayName("'not paid' while ZynlePay still says 'deciding' is refused unless the owner insists; with no answer at all nothing is decided; then the order lets go")
    void notPaid() throws Exception {
        UUID id = unanswered();

        MvcResult refused = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("NOT_PAID", null));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(refused)).isEqualTo("provider-says-pending");
        assertThat(row(id).get("status")).isEqualTo("UNRESOLVED");

        zynlePay.statusOf(reference(id), new NoAnswer("no answer: HttpTimeoutException"));
        jdbc.update("UPDATE provider_payment SET check_lease_until = NULL, last_checked_at = NULL WHERE id = ?", id);
        MvcResult silent = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("NOT_PAID", "\"overridePending\":true"));
        assertThat(silent.getResponse().getStatus()).isEqualTo(503);
        assertThat(problem(silent)).isEqualTo("provider-unavailable");
        assertThat(row(id).get("status")).isEqualTo("UNRESOLVED");

        zynlePay.statusOf(reference(id), new PaymentProvider.Pending("990"));
        MvcResult insisted = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("NOT_PAID", "\"overridePending\":true"));
        assertThat(insisted.getResponse().getStatus()).as(insisted.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(row(id).get("status")).isEqualTo("FAILED");
        assertThat(row(id).get("failure_code")).isEqualTo("MARKED_NOT_PAID");
        assertThat(row(id).get("holds_order")).isEqualTo(false);
        assertThat(updates(id)).last().satisfies(payload -> assertThat(payload).contains("\"status\":\"FAILED\"").contains("\"failureCode\":\"MARKED_NOT_PAID\""));
        assertThat(jdbc.queryForObject("SELECT override_pending FROM provider_payment_resolution WHERE payment_id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("ZynlePay said paid for K181.00 when K180.00 was asked: the owner records K181.00 (fee K4.53) or K180.00, and no other figure")
    void anotherAmount() throws Exception {
        UUID id = pending();
        zynlePay.statusOf(reference(id), new Paid("100", new BigDecimal("181.00"), "ZMW", null, null, null, "MTN"));
        checks.checkOne(id, Instant.now(), "STAFF", "user:kc-sara");
        assertThat(row(id).get("unresolved_reason")).isEqualTo("AMOUNT_DIFFERS");
        jdbc.update("UPDATE provider_payment SET last_checked_at = NULL WHERE id = ?", id);

        MvcResult invented = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("PAID", "\"gross\":500.00"));
        assertThat(invented.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(invented)).isEqualTo("gross-not-on-record");

        MvcResult recorded = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("PAID", "\"gross\":181.00"));
        assertThat(recorded.getResponse().getStatus()).as(recorded.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(confirmations(id)).singleElement().satisfies(money -> {
            assertThat((BigDecimal) money.get("gross")).isEqualByComparingTo("181.00");
            assertThat((BigDecimal) money.get("fee")).isEqualByComparingTo("4.53");
            assertThat(money.get("confirmed_by")).isEqualTo("user:kc-olive");
        });

        // A figure is typed only where ZynlePay reported another one.
        UUID plain = unanswered();
        MvcResult notAsked = resolve(olive(), plain, "res-" + UUID.randomUUID(), decision("PAID", "\"gross\":180.00"));
        assertThat(notAsked.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(notAsked)).isEqualTo("gross-not-on-record");
    }

    @Test
    @DisplayName("a payment whose record the owner cancelled is decided by the owner alone, without asking ZynlePay again: recorded anew, or marked not paid")
    void afterTheOwnerCancelledItsRecord() throws Exception {
        UUID again = pending();
        zynlePay.statusOf(reference(again), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));
        checks.checkOne(again, Instant.now(), "STAFF", "user:kc-sara");
        UUID first = (UUID) row(again).get("confirmation_id");
        assertThat(mvc.perform(post("/api/v1/admin/payments/confirmations/" + first + "/void").with(olive()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"ZynlePay's statement shows no such payment\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(row(again).get("unresolved_reason")).isEqualTo("CONFIRMATION_VOIDED");
        int askedBefore = zynlePay.statusCalls(reference(again));

        MvcResult anew = resolve(olive(), again, "res-" + UUID.randomUUID(), decision("PAID", null));

        assertThat(anew.getResponse().getStatus()).as(anew.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(zynlePay.statusCalls(reference(again))).as("ZynlePay would only repeat the answer in doubt").isEqualTo(askedBefore);
        assertThat(row(again).get("status")).isEqualTo("VERIFIED");
        assertThat(row(again).get("confirmation_id")).isNotEqualTo(first);
        assertThat(confirmations(again)).hasSize(2).last().satisfies(money -> {
            assertThat(money.get("idempotency_key")).isEqualTo("zynlepay:" + reference(again) + ":2");
            assertThat(money.get("confirmed_by")).isEqualTo("user:kc-olive");
        });

        UUID never = pending();
        zynlePay.statusOf(reference(never), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));
        checks.checkOne(never, Instant.now(), "STAFF", "user:kc-sara");
        mvc.perform(post("/api/v1/admin/payments/confirmations/" + row(never).get("confirmation_id") + "/void").with(olive())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ZynlePay's statement shows no such payment\"}")).andReturn();
        MvcResult notPaid = resolve(olive(), never, "res-" + UUID.randomUUID(), decision("NOT_PAID", null));
        assertThat(notPaid.getResponse().getStatus()).as(notPaid.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(row(never).get("failure_code")).isEqualTo("MARKED_NOT_PAID");
        assertThat(row(never).get("next_check_at")).isNull();
        // And nobody's "check now" puts the owner's word back in doubt (ZynlePay would say "paid" again, and it would land on the owner again).
        MvcResult checked = mvc.perform(post(BASE + "/" + never + "/check").with(sara())).andReturn();
        assertThat(checked.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(checked)).isEqualTo("provider-payment-final");
        assertThat(row(never).get("failure_code")).isEqualTo("MARKED_NOT_PAID");
    }

    @Test
    @DisplayName("money staff already keyed under the network's own reference is not recorded a second time by the owner either; it is marked not paid without asking ZynlePay")
    void theSameMoneyIsAlreadyOnRecord() throws Exception {
        UUID id = pending();
        String theirs = "MP" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        UUID keyed = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by, confirmed_at,
                                                  idempotency_key, request_fingerprint)
                VALUES (?, ?, 'MTN', ?, ?, 180.00, 0, 'ZMW', now(), 'user:kc-sara', now(), ?, 'fp')""", keyed, UUID.randomUUID(), theirs, "MTN:" + theirs,
                "idem-" + UUID.randomUUID());
        zynlePay.statusOf(reference(id), new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, theirs, "MTN"));
        checks.checkOne(id, Instant.now(), "STAFF", "user:kc-sara");
        assertThat(row(id).get("unresolved_reason")).isEqualTo("SAME_MONEY_ON_RECORD");
        int askedBefore = zynlePay.statusCalls(reference(id));

        MvcResult twice = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("PAID", null));
        assertThat(twice.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(twice)).isEqualTo("same-money-on-record");
        assertThat(body(twice).get("confirmationId").asString()).isEqualTo(keyed.toString());
        assertThat(confirmations(id)).isEmpty();
        assertThat(row(id).get("status")).as("nothing of the refused decision is kept").isEqualTo("UNRESOLVED");

        MvcResult notPaid = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("NOT_PAID", null));
        assertThat(notPaid.getResponse().getStatus()).as(notPaid.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(row(id).get("failure_code")).isEqualTo("MARKED_NOT_PAID");
        assertThat(zynlePay.statusCalls(reference(id))).as("ZynlePay would only say 'paid' again").isEqualTo(askedBefore);
    }

    @Test
    @DisplayName("a failed payment the owner knows was paid is recorded on the owner's word; one still in flight, or never sent, is not the owner's to decide")
    void whichPaymentsTheOwnerDecides() throws Exception {
        UUID declined = pending();
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'DECLINED', holds_order = false, failed_at = now() WHERE id = ?", declined);
        MvcResult recorded = resolve(olive(), declined, "res-" + UUID.randomUUID(), decision("PAID", null));
        assertThat(recorded.getResponse().getStatus()).as(recorded.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(row(declined).get("status")).isEqualTo("VERIFIED");
        assertThat(confirmations(declined)).hasSize(1);
        MvcResult notPaidOfFailed = resolve(olive(), declined, "res-" + UUID.randomUUID(), decision("NOT_PAID", null));
        assertThat(notPaidOfFailed.getResponse().getStatus()).isEqualTo(409);

        UUID inFlight = pending();
        MvcResult tooSoon = resolve(olive(), inFlight, "res-" + UUID.randomUUID(), decision("PAID", null));
        assertThat(tooSoon.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(tooSoon)).isEqualTo("not-resolvable");

        UUID neverSent = pending();
        jdbc.update("UPDATE provider_payment SET status = 'FAILED', failure_code = 'NOT_SENT', holds_order = false, failed_at = now() WHERE id = ?", neverSent);
        assertThat(problem(resolve(olive(), neverSent, "res-" + UUID.randomUUID(), decision("PAID", null)))).isEqualTo("not-resolvable");
        assertThat(resolve(olive(), UUID.randomUUID(), "res-" + UUID.randomUUID(), decision("PAID", null)).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a decision needs written evidence and a date that can be right: under ten characters, before the payment started, or over 72 hours back without leave, is refused")
    void whatADecisionNeeds() throws Exception {
        UUID id = unanswered();

        assertThat(resolve(olive(), id, "res-" + UUID.randomUUID(), "{\"outcome\":\"PAID\",\"evidence\":\"it's fine\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(resolve(olive(), id, "res-" + UUID.randomUUID(), "{\"outcome\":\"MAYBE\",\"evidence\":\"" + EVIDENCE + "\"}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(mvc.perform(post(BASE + "/" + id + "/resolve").with(olive()).contentType(MediaType.APPLICATION_JSON).content(decision("PAID", null)))
                .andReturn().getResponse().getStatus()).as("no Idempotency-Key").isEqualTo(400);
        MvcResult before = resolve(olive(), id, "res-" + UUID.randomUUID(), decision("PAID", "\"arrivedAt\":\"" + sent.minusSeconds(3_600) + "\""));
        assertThat(before.getResponse().getStatus()).isEqualTo(400);
        assertThat(confirmations(id)).isEmpty();

        // A payment started four days ago, decided today as having arrived then: a late date, which needs leave to back-date.
        jdbc.update("UPDATE provider_payment SET last_checked_at = NULL WHERE id = ?", id);
        UUID old = UUID.randomUUID();
        Instant fourDaysAgo = Instant.now().minus(Duration.ofDays(4)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, fee_percent,
                                              fee_fixed, status, unresolved_reason, idempotency_key, request_fingerprint, started_at, sent_at, unresolved_at)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', 180.00, 'ZMW', 2.5, 0.00, 'UNRESOLVED', 'NO_ANSWER', ?, 'fp',
                        ?, ?, ?)""", old, ProviderReference.mint(), UUID.randomUUID(), "key-" + old, Timestamp.from(fourDaysAgo), Timestamp.from(fourDaysAgo),
                Timestamp.from(fourDaysAgo.plusSeconds(1_800)));
        String late = decision("PAID", "\"arrivedAt\":\"" + fourDaysAgo.plusSeconds(600) + "\"");
        assertThat(resolve(olive(), old, "res-" + UUID.randomUUID(), late).getResponse().getStatus()).isEqualTo(403);
        assertThat(confirmations(old)).isEmpty();
        MvcResult allowed = resolve(oliveWhoMayBackdate(), old, "res-" + UUID.randomUUID(), late);
        assertThat(allowed.getResponse().getStatus()).as(allowed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(confirmations(old)).singleElement().satisfies(money -> {
            assertThat(((Timestamp) money.get("arrived_at")).toInstant()).isEqualTo(fourDaysAgo.plusSeconds(600));
            assertThat(money.get("backdate_reason")).isEqualTo("recorded late by the owner from ZynlePay's records");
        });
    }
}
