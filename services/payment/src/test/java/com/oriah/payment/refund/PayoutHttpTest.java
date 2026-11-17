package com.oriah.payment.refund;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.events.EventHeaders;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.confirmation.ConfirmationService;
import com.oriah.payment.confirmation.SettlementOutcomes;
import com.oriah.payment.confirmation.Staff;
import com.oriah.payment.dto.ConfirmPaymentRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paying money back (Phase 12): a refund that is owed, or money held for a customer. The owner (PAYMENT_ADMIN) says how it was sent, what the
 * provider charged to send it and how much of that the customer bore; the amount paid back is the debt's own and is never typed. A payout
 * recorded wrongly is cancelled (the debt is owed again) and recorded afresh. A debt whose money record was cancelled after it paid the sale
 * waits for the owner's word on whether the money ever arrived.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
class PayoutHttpTest {

    private static final String PAYOUTS = "/api/v1/admin/payments/payouts";
    private static final String DUES = "/api/v1/admin/payments/refunds-due";
    private static final String PROBLEMS = "https://oriah.dev/problems/";
    private static final AtomicLong SEQ = new AtomicLong(60_000);
    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    private MockMvc mvc;
    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private ConfirmationService confirmations;
    @Autowired
    private SettlementOutcomes outcomes;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ---------------------------------------------------------------- fixtures

    private static RequestPostProcessor owner() {
        return jwt().jwt(j -> j.subject("kc-olive").claim("name", "Olive Mwale")).authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"));
    }

    private static RequestPostProcessor ownerWhoMayBackdate() {
        return jwt().jwt(j -> j.subject("kc-olive").claim("name", "Olive Mwale"))
                .authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE"));
    }

    private static String reference() {
        return "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private UUID confirmed(UUID order, String gross) {
        return confirmations.confirm(new ConfirmPaymentRequest(order, "MTN", reference(), new BigDecimal(gross), new BigDecimal("3.40"), null, null, null, null),
                "key-" + UUID.randomUUID(), SAM).view().id();
    }

    private void deliver(String type, int version, UUID orderId, String payload) {
        orderEvents.accept(MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, UUID.randomUUID().toString()).setHeader(EventHeaders.EVENT_TYPE, type)
                .setHeader(EventHeaders.AGGREGATE_TYPE, "order").setHeader(EventHeaders.AGGREGATE_ID, orderId.toString())
                .setHeader(EventHeaders.SEQ, Long.toString(SEQ.incrementAndGet())).setHeader(EventHeaders.OUTBOX_ID, Long.toString(SEQ.get()))
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-05T09:15:00Z").setHeader(EventHeaders.RECORDED_AT, "2026-11-05T09:15:00.125Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-olive").setHeader(EventHeaders.SCHEMA_VERSION, Integer.toString(version))
                .setHeader(EventHeaders.PRODUCER, "order").setHeader(EventHeaders.KAFKA_KEY, orderId.toString().getBytes(StandardCharsets.UTF_8)).build());
    }

    /** A debt of {@code owed}, owed since an hour ago, on a sale paid by a fresh confirmation; whether that money record was cancelled first. */
    private UUID debt(String owed, String bearer, boolean moneyRecordCancelled) {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, owed);
        if (moneyRecordCancelled) {
            confirmations.voidConfirmation(confirmation, "keyed against the wrong order", SAM);
        }
        deliver("order.paid", 2, order, "{\"orderId\":\"" + order + "\",\"confirmationId\":\"" + confirmation + "\"}");
        UUID correction = UUID.randomUUID();
        Instant since = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        deliver("order.sale_cancelled", 1, order, "{\"correctionId\":\"" + correction + "\",\"orderId\":\"" + order + "\",\"kind\":\"CANCELLATION\","
                + "\"confirmationId\":\"" + confirmation + "\",\"occurredAt\":\"" + since + "\",\"currency\":\"ZMW\",\"owedGross\":" + owed + ",\"feeBearer\":\"" + bearer + "\"}");
        return correction;
    }

    private UUID debt(String owed) {
        return debt(owed, "CUSTOMER", false);
    }

    /** Money held for a customer: the held record's id. */
    private UUID held(String gross, String reason) {
        UUID confirmation = confirmed(UUID.randomUUID(), gross);
        outcomes.unsettled(confirmation, reason);
        return jdbc.queryForObject("SELECT id FROM held_money WHERE confirmation_id = ?", UUID.class, confirmation);
    }

    private ResultActions pay(RequestPostProcessor who, String key, String json) throws Exception {
        var request = post(PAYOUTS).with(who).contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
    }

    private ResultActions pay(String json) throws Exception {
        return pay(owner(), "key-" + UUID.randomUUID(), json);
    }

    private static String refund(UUID due, String method, String reference, String fee, String send) {
        return "{\"refundDueId\":\"" + due + "\",\"method\":\"" + method + "\"" + (reference == null ? "" : ",\"reference\":\"" + reference + "\"")
                + ",\"customerFee\":" + fee + ",\"sendCharge\":" + send + "}";
    }

    private static String heldBack(UUID held, String method, String reference, String fee, String send) {
        return refund(held, method, reference, fee, send).replace("refundDueId", "heldMoneyId");
    }

    private UUID payoutOf(ResultActions paid) throws Exception {
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(paid.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private String statusOf(UUID due) {
        return jdbc.queryForObject("SELECT status FROM refund_due WHERE id = ?", String.class, due);
    }

    private List<Map<String, Object>> events(String aggregateType, UUID id) {
        return jdbc.queryForList("SELECT event_type, actor, occurred_at, payload::text AS payload FROM outbox WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY seq",
                aggregateType, id.toString());
    }

    // ---------------------------------------------------------------- what is owed

    @Test
    @DisplayName("the owner sees what is owed, oldest first, with how the money first came in; the staff who confirm payments, and shoppers, do not")
    void theOwnerSeesWhatIsOwed() throws Exception {
        UUID due = debt("225.00");

        mvc.perform(get(DUES).with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + due + "')].owedGross").value(225.00))
                .andExpect(jsonPath("$[?(@.id=='" + due + "')].status").value("OWED"))
                .andExpect(jsonPath("$[?(@.id=='" + due + "')].kind").value("CANCELLATION"))
                .andExpect(jsonPath("$[?(@.id=='" + due + "')].feeBearer").value("CUSTOMER"))
                .andExpect(jsonPath("$[?(@.id=='" + due + "')].originalMethod").value("MTN"));
        mvc.perform(get(DUES + "/" + due).with(owner())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(due.toString()))
                .andExpect(jsonPath("$.originalFee").value(3.40));
        mvc.perform(get(DUES + "/" + UUID.randomUUID()).with(owner())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "refund-due-not-found"));
        mvc.perform(get(DUES).param("status", "PAID").with(owner())).andExpect(jsonPath("$[?(@.id=='" + due + "')]").isEmpty());
        mvc.perform(get(DUES).param("status", "SPENT").with(owner())).andExpect(status().isBadRequest());

        mvc.perform(get(DUES)).andExpect(status().isUnauthorized());
        for (String role : new String[] {"ROLE_SALES_AGENT", "ROLE_USER", "ROLE_FINANCE_ADMIN", "ROLE_ORDER_ADMIN"}) {
            mvc.perform(get(DUES).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
            pay(jwt().authorities(new SimpleGrantedAuthority(role)), "key-" + UUID.randomUUID(), refund(due, "CASH", null, "0.00", "0.00")).andExpect(status().isForbidden());
        }
        assertThat(statusOf(due)).isEqualTo("OWED");
    }

    // ---------------------------------------------------------------- paying a refund

    @Test
    @DisplayName("paying a refund: K225.00 owed, sent by MTN, MTN charged K5.00 and the customer bore it: the customer receives K220.00, the debt is paid, and it is announced")
    void payingARefund() throws Exception {
        UUID due = debt("225.00");
        String ref = reference();
        String key = "key-" + UUID.randomUUID();
        String body = refund(due, "MTN", " " + ref.toLowerCase() + " ", "5.00", "5.00");

        ResultActions paid = pay(owner(), key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("REFUND"))
                .andExpect(jsonPath("$.refundDueId").value(due.toString()))
                .andExpect(jsonPath("$.gross").value(225.00))
                .andExpect(jsonPath("$.customerFee").value(5.00))
                .andExpect(jsonPath("$.net").value(220.00))
                .andExpect(jsonPath("$.sendCharge").value(5.00))
                .andExpect(jsonPath("$.feeBearer").value("CUSTOMER"))
                .andExpect(jsonPath("$.method").value("MTN"))
                .andExpect(jsonPath("$.reference").value(ref))
                .andExpect(jsonPath("$.paidBy").value("user:kc-olive"))
                .andExpect(jsonPath("$.paidByName").value("Olive Mwale"))
                .andExpect(jsonPath("$.voided").value(false));
        UUID payout = payoutOf(paid);

        assertThat(statusOf(due)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT paid_money_out_id FROM refund_due WHERE id = ?", UUID.class, due)).isEqualTo(payout);
        assertThat(events("money_out", payout)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "payment.paid_out").containsEntry("actor", "user:kc-olive");
            assertThat((String) event.get("payload")).contains("\"purpose\":\"REFUND\"").contains("\"debtId\":\"" + due + "\"").contains("\"cause\":\"CANCELLATION\"")
                    .contains("\"gross\":225.00").contains("\"customerFee\":5.00").contains("\"net\":220.00").contains("\"sendCharge\":5.00")
                    .doesNotContain(ref).doesNotContain("Olive");
        });
        // The same request again is the same payout; the list shows it as paid.
        pay(owner(), key, body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(payout.toString()));
        pay(owner(), key, refund(due, "MTN", ref, "4.00", "5.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "idempotency-key-reused"));
        mvc.perform(get(DUES + "/" + due).with(owner())).andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.payout.net").value(220.00));
        mvc.perform(get(PAYOUTS + "/" + payout).with(owner())).andExpect(status().isOk()).andExpect(jsonPath("$.net").value(220.00));
        pay(refund(due, "CASH", null, "0.00", "0.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "already-paid-out"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM money_out WHERE refund_due_id = ?", Long.class, due)).isEqualTo(1);
    }

    @Test
    @DisplayName("what is refused when paying: an amount typed, no key, a fee above the charge, a fee when Oriah bears it, a charge on cash, no reference, a reference used before, a date before it was owed")
    void whatIsRefused() throws Exception {
        UUID due = debt("90.00");
        UUID oriahBears = debt("90.00", "ORIAH", false);

        pay(refund(due, "MTN", reference(), "2.50", "2.50").replace("}", ",\"gross\":90.00}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "amount-is-not-typed"));
        pay(refund(due, "MTN", reference(), "2.50", "2.50").replace("}", ",\"net\":87.50}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "amount-is-not-typed"));
        pay(owner(), null, refund(due, "MTN", reference(), "2.50", "2.50")).andExpect(status().isBadRequest());
        pay(refund(due, "MTN", reference(), "3.00", "2.50")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-customer-fee"));
        pay(refund(due, "MTN", reference(), "90.00", "90.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-customer-fee"));
        pay(refund(due, "MTN", reference(), "0.00", "500.01")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-send-charge"));
        pay(refund(due, "CASH", null, "0.00", "2.00")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "cash-has-no-charge"));
        pay(refund(due, "MTN", null, "0.00", "2.00")).andExpect(status().isBadRequest());
        pay(refund(due, "PAYPAL", reference(), "0.00", "2.00")).andExpect(status().isBadRequest());
        pay("{\"method\":\"CASH\",\"customerFee\":0.00,\"sendCharge\":0.00}").andExpect(status().isBadRequest());   // neither a refund nor held money
        pay(refund(due, "MTN", reference(), "2.50", "2.50").replace("}", ",\"paidAt\":\"" + Instant.now().minus(Duration.ofHours(2)) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "paid-before-it-was-owed"));
        pay(refund(oriahBears, "MTN", reference(), "2.50", "2.50")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "oriah-bears-this-charge"));
        pay(refund(UUID.randomUUID(), "CASH", null, "0.00", "0.00")).andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "refund-due-not-found"));
        assertThat(statusOf(due)).isEqualTo("OWED");
        assertThat(statusOf(oriahBears)).isEqualTo("OWED");

        // Oriah bearing the charge pays the full amount; a reference pays out once.
        String ref = reference();
        pay(refund(oriahBears, "AIRTEL", ref, "0.00", "2.50")).andExpect(status().isCreated()).andExpect(jsonPath("$.net").value(90.00))
                .andExpect(jsonPath("$.feeBearer").value("ORIAH"));
        pay(refund(due, "AIRTEL", ref, "0.00", "2.50")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "payout-reference-already-used"));
        // Cash costs nothing to hand over and needs no reference.
        pay(refund(due, "CASH", null, "0.00", "0.00")).andExpect(status().isCreated()).andExpect(jsonPath("$.net").value(90.00)).andExpect(jsonPath("$.reference").doesNotExist());
    }

    @Test
    @DisplayName("a payout dated more than 72 hours back needs the right to back-date and a reason, like money coming in")
    void anOldDateNeedsTheRightAndAReason() throws Exception {
        UUID order = UUID.randomUUID();
        UUID confirmation = confirmed(order, "90.00");
        deliver("order.paid", 2, order, "{\"orderId\":\"" + order + "\",\"confirmationId\":\"" + confirmation + "\"}");
        UUID due = UUID.randomUUID();
        Instant since = Instant.now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
        deliver("order.return_recorded", 1, order, "{\"correctionId\":\"" + due + "\",\"orderId\":\"" + order + "\",\"kind\":\"RETURN\",\"confirmationId\":\""
                + confirmation + "\",\"occurredAt\":\"" + since + "\",\"currency\":\"ZMW\",\"owedGross\":90.00,\"feeBearer\":\"CUSTOMER\"}");
        String old = ",\"paidAt\":\"" + Instant.now().minus(Duration.ofDays(5)).truncatedTo(ChronoUnit.SECONDS) + "\"";

        pay(refund(due, "CASH", null, "0.00", "0.00").replace("}", old + "}")).andExpect(status().isForbidden());
        pay(ownerWhoMayBackdate(), "key-" + UUID.randomUUID(), refund(due, "CASH", null, "0.00", "0.00").replace("}", old + "}")).andExpect(status().isBadRequest());
        pay(ownerWhoMayBackdate(), "key-" + UUID.randomUUID(), refund(due, "CASH", null, "0.00", "0.00").replace("}", old + ",\"backdateReason\":\"paid in cash on Friday, keyed today\"}"))
                .andExpect(status().isCreated());
        assertThat(statusOf(due)).isEqualTo("PAID");
    }

    // ---------------------------------------------------------------- a payout cancelled

    @Test
    @DisplayName("a payout recorded wrongly is cancelled once, with a reason: the debt is owed again, the reference is free, and it is announced; the typed reason is never logged")
    void aPayoutCancelled(CapturedOutput output) throws Exception {
        UUID due = debt("225.00");
        String ref = reference();
        UUID payout = payoutOf(pay(refund(due, "MTN", ref, "5.00", "5.00")).andExpect(status().isCreated()));
        String reason = "sent to Mary Banda's old number 0977000111 by mistake";

        mvc.perform(post(PAYOUTS + "/" + payout + "/void").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.voided").value(true)).andExpect(jsonPath("$.voidReason").value(reason));

        assertThat(statusOf(due)).isEqualTo("OWED");
        assertThat(jdbc.queryForObject("SELECT paid_money_out_id FROM refund_due WHERE id = ?", UUID.class, due)).isNull();
        assertThat(events("money_out", payout)).extracting(event -> event.get("event_type")).containsExactly("payment.paid_out", "payment.payout_voided");
        assertThat((String) events("money_out", payout).get(1).get("payload")).contains("\"voidedAt\"").doesNotContain("Mary").doesNotContain("0977000111").doesNotContain(ref);
        assertThat(output.getAll()).doesNotContain("Mary Banda").doesNotContain("0977000111");
        mvc.perform(post(PAYOUTS + "/" + payout + "/void").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "payout-already-voided"));
        mvc.perform(post(PAYOUTS + "/" + UUID.randomUUID() + "/void").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "payout-not-found"));
        mvc.perform(post(PAYOUTS + "/" + payout + "/void").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"oops\"}"))
                .andExpect(status().isBadRequest());

        // Recorded afresh, with the same reference: it is free again.
        pay(refund(due, "MTN", ref, "5.00", "5.00")).andExpect(status().isCreated());
        assertThat(statusOf(due)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM money_out WHERE refund_due_id = ?", Long.class, due)).isEqualTo(2);
    }

    @Test
    @DisplayName("two payouts of one debt sent at the same moment: exactly one stands")
    void twoPayoutsOfOneDebtAtOnce() throws Exception {
        UUID due = debt("90.00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Integer> one = () -> {
                go.await();
                return pay(refund(due, "MTN", reference(), "2.50", "2.50")).andReturn().getResponse().getStatus();
            };
            Future<Integer> first = pool.submit(one);
            Future<Integer> second = pool.submit(one);
            go.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM money_out WHERE refund_due_id = ?", Long.class, due)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the money check

    @Test
    @DisplayName("a debt in question is not paid until the owner says the money arrived; saying so, once, makes it payable")
    void arrivedMakesItPayable() throws Exception {
        UUID due = debt("180.00", "CUSTOMER", true);
        assertThat(statusOf(due)).isEqualTo("IN_QUESTION");
        pay(refund(due, "CASH", null, "0.00", "0.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "money-check-needed"));

        mvc.perform(post(DUES + "/" + due + "/money-check").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneyArrived\":true,\"reason\":\"the MTN statement shows it on 3 November\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OWED")).andExpect(jsonPath("$.check.moneyArrived").value(true));

        mvc.perform(post(DUES + "/" + due + "/money-check").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneyArrived\":false,\"reason\":\"on second thoughts it did not\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "already-checked"));
        pay(refund(due, "CASH", null, "0.00", "0.00")).andExpect(status().isCreated());
        assertThat(events("refund_due", due)).extracting(event -> event.get("event_type")).containsExactly("payment.refund_due_recorded");
    }

    @Test
    @DisplayName("the owner says the money never arrived: the debt is closed, nothing can be paid on it, and it is announced; a debt that is simply owed has nothing to check")
    void neverArrivedClosesItAndAnnounces() throws Exception {
        UUID due = debt("180.00", "CUSTOMER", true);

        mvc.perform(post(DUES + "/" + due + "/money-check").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneyArrived\":false,\"reason\":\"no such payment on the MTN statement\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.check.moneyArrived").value(false));

        assertThat(events("refund_due", due)).extracting(event -> event.get("event_type")).containsExactly("payment.refund_due_recorded", "payment.refund_closed");
        assertThat((String) events("refund_due", due).get(1).get("payload")).contains("\"outcome\":\"MONEY_NEVER_ARRIVED\"").contains("\"owedGross\":180.00")
                .doesNotContain("statement");
        pay(refund(due, "CASH", null, "0.00", "0.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "refund-closed"));

        UUID plain = debt("90.00");
        mvc.perform(post(DUES + "/" + plain + "/money-check").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moneyArrived\":true,\"reason\":\"just checking it anyway\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "not-in-question"));
        mvc.perform(post(DUES + "/" + due + "/money-check").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"moneyArrived\":true,\"reason\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(DUES + "/" + due + "/money-check").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"moneyArrived\":true,\"reason\":\"the statement shows it\"}")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- held money

    @Test
    @DisplayName("held money is paid back the same way: who bears the sending charge comes from why it was held; a wrong-date record is voided and keyed again, never repaid")
    void repayingHeldMoney() throws Exception {
        UUID wrongAmount = held("85.00", "WRONG_AMOUNT");
        UUID pieceSold = held("85.00", "PIECE_SOLD");
        UUID wrongDate = held("85.00", "WRONG_DATE");

        UUID payout = payoutOf(pay(heldBack(wrongAmount, "MTN", reference(), "2.00", "2.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("HELD_MONEY")).andExpect(jsonPath("$.heldMoneyId").value(wrongAmount.toString()))
                .andExpect(jsonPath("$.gross").value(85.00)).andExpect(jsonPath("$.net").value(83.00)).andExpect(jsonPath("$.feeBearer").value("CUSTOMER")));
        assertThat(jdbc.queryForObject("SELECT status FROM held_money WHERE id = ?", String.class, wrongAmount)).isEqualTo("REPAID");
        assertThat((String) events("money_out", payout).getFirst().get("payload")).contains("\"purpose\":\"HELD_MONEY\"").contains("\"cause\":\"WRONG_AMOUNT\"")
                .contains("\"debtId\":\"" + wrongAmount + "\"");
        pay(heldBack(wrongAmount, "CASH", null, "0.00", "0.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "already-paid-out"));

        // The piece was sold while the money was on its way: the delay was Oriah's, so the customer receives all of it.
        pay(heldBack(pieceSold, "MTN", reference(), "2.00", "2.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "oriah-bears-this-charge"));
        pay(heldBack(pieceSold, "MTN", reference(), "0.00", "2.00")).andExpect(status().isCreated()).andExpect(jsonPath("$.net").value(85.00));

        pay(heldBack(wrongDate, "CASH", null, "0.00", "0.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "void-and-key-again"));
        pay(heldBack(UUID.randomUUID(), "CASH", null, "0.00", "0.00")).andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "held-money-not-found"));

        mvc.perform(get("/api/v1/admin/payments/held-money").param("status", "REPAID").with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + wrongAmount + "')].status").value("REPAID"))
                .andExpect(jsonPath("$[?(@.id=='" + wrongAmount + "')].feeBearer").value("CUSTOMER"))
                .andExpect(jsonPath("$[?(@.id=='" + wrongAmount + "')].payoutId").value(payout.toString()));
    }

    @Test
    @DisplayName("held money that was paid back cannot have its confirmation voided, and held money whose confirmation was voided cannot be paid back; cancelling the payout makes it owed again")
    void heldMoneyRepaidOrVoidedNeverBoth() throws Exception {
        UUID repaid = held("85.00", "WRONG_AMOUNT");
        UUID repaidConfirmation = jdbc.queryForObject("SELECT confirmation_id FROM held_money WHERE id = ?", UUID.class, repaid);
        UUID payout = payoutOf(pay(heldBack(repaid, "CASH", null, "0.00", "0.00")).andExpect(status().isCreated()));

        mvc.perform(post("/api/v1/admin/payments/confirmations/" + repaidConfirmation + "/void").with(jwt().jwt(j -> j.subject("kc-sam"))
                        .authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"))).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"keyed against the wrong order\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "held-money-repaid"));

        UUID voided = held("60.00", "ORDER_UNKNOWN");
        confirmations.voidConfirmation(jdbc.queryForObject("SELECT confirmation_id FROM held_money WHERE id = ?", UUID.class, voided), "keyed against the wrong order", SAM);
        pay(heldBack(voided, "CASH", null, "0.00", "0.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "held-money-voided"));

        mvc.perform(post(PAYOUTS + "/" + payout + "/void").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"handed to the wrong person\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT status, repaid_at FROM held_money WHERE id = ?", repaid)).containsEntry("status", "OWED").containsEntry("repaid_at", null);
    }

    // ---------------------------------------------------------------- a reference that paid a sale after its record was cancelled

    @Test
    @DisplayName("a reference whose cancelled money record paid a sale after all is never keyed a second time: the same money cannot be counted, and repaid, twice")
    void aReferenceThatPaidASaleAfterItsVoidIsNotReused() throws Exception {
        UUID order = UUID.randomUUID();
        String ref = reference();
        UUID confirmation = confirmations.confirm(new ConfirmPaymentRequest(order, "MTN", ref, new BigDecimal("180.00"), new BigDecimal("3.40"), null, null, null, null),
                "key-" + UUID.randomUUID(), SAM).view().id();
        confirmations.voidConfirmation(confirmation, "keyed against the wrong order", SAM);
        deliver("order.paid", 2, order, "{\"orderId\":\"" + order + "\",\"confirmationId\":\"" + confirmation + "\"}");

        mvc.perform(post("/api/v1/admin/payments/confirmations").with(jwt().jwt(j -> j.subject("kc-sam")).authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"method\":\"MTN\",\"reference\":\"" + ref + "\",\"gross\":180.00}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "reference-paid-a-sale"));
    }
}
