package com.oriah.notification.alerts;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.oriah.events.EventHeaders;
import com.oriah.events.EventsProperties;
import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.delivery.DispatchHealth;
import com.oriah.notification.delivery.Email;
import com.oriah.notification.delivery.MessageDispatcher;
import com.oriah.notification.delivery.MessageStore;
import com.oriah.notification.delivery.RecordingTransport;
import com.oriah.notification.events.PaymentEventConsumer;
import com.oriah.notification.events.Relayed;
import com.oriah.notification.orders.OrderRefs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money staff recorded, and what became of it, heard from two topics.
 *
 * <p>Payment announces a confirmation and keeps announcing it until order answers ({@code payment.confirmed}, attempt 1, 2, 3 ...). A SECOND
 * announcement with no answer is payment's own way of saying "the sale has not completed": staff are told once, told again after some hours
 * and then daily, told "sorted" when an answer comes, and given a last note if none ever does. An answer is the sale paid
 * ({@code order.paid} naming the confirmation), the money held for the customer ({@code order.payment_unsettled}, {@code payment.held}), or the
 * record cancelled ({@code payment.voided}). A cancelled record that paid a sale anyway is an alert of its own.
 */
@ServiceTest
class PaymentEventsTest {

    private static final Instant START = MutableClock.START;
    private static final List<String> MONEY = List.of("alerts-money@oriah.local", "alerts-owner@oriah.local");

    @Autowired
    @Qualifier("paymentEvents")
    private Consumer<Message<byte[]>> paymentEvents;
    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private ConfirmationWatch watch;
    @Autowired
    private MessageStore store;
    @Autowired
    private ContactBook contacts;
    @Autowired
    private OrderRefs orderRefs;
    @Autowired
    private DispatchHealth health;
    @Autowired
    private NotificationSettings settings;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private BindingServiceProperties bindings;
    @Autowired
    private EventsProperties events;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final RecordingTransport transport = new RecordingTransport();
    private final UUID orderId = UUID.randomUUID();
    private final UUID confirmationId = UUID.randomUUID();
    private final String code = orderId.toString().substring(0, 8).toUpperCase();
    private long seq;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------------

    private MessageDispatcher dispatcher(String supportLine) {
        EmailSetup setup = new EmailSetup(Mode.CATCHER, null, MONEY, List.of("alerts-cost@oriah.local"), supportLine, false);
        StaffAlerts alerts = new StaffAlerts(jdbc, transactions, setup, settings);
        return new MessageDispatcher(store, contacts, orderRefs, health, alerts, watch, transport, setup, settings, clock);
    }

    private MessageDispatcher dispatcher() {
        return dispatcher(null);
    }

    /** The time the producer says it recorded a fact "now", by the test clock. */
    private String justNow() {
        return clock.instant().minusSeconds(1).toString();
    }

    private void confirmed(int attempt) {
        confirmed(attempt, justNow());
    }

    private void confirmed(int attempt, String recordedAt) {
        payment("payment.confirmed", "{\"confirmationId\":\"" + confirmationId + "\",\"orderId\":\"" + orderId + "\",\"method\":\"MTN\","
                + "\"reference\":\"MP2411ABC\",\"gross\":180.00,\"fee\":2.50,\"net\":177.50,\"currency\":\"ZMW\",\"arrivedAt\":\"2026-11-09T07:00:00Z\","
                + "\"backdateReason\":null,\"confirmedBy\":\"user:kc-maya\",\"confirmedAt\":\"2026-11-09T07:29:00Z\",\"attempt\":" + attempt + "}", recordedAt);
    }

    private void held(String reason) {
        payment("payment.held", "{\"confirmationId\":\"" + confirmationId + "\",\"orderId\":\"" + orderId + "\",\"reason\":\"" + reason + "\","
                + "\"owedGross\":180.00,\"currency\":\"ZMW\",\"heldAt\":\"2026-11-09T07:31:00Z\"}", justNow());
    }

    private void voided() {
        payment("payment.voided", "{\"confirmationId\":\"" + confirmationId + "\",\"orderId\":\"" + orderId + "\",\"method\":\"MTN\","
                + "\"reference\":\"MP2411ABC\",\"gross\":180.00,\"fee\":2.50,\"net\":177.50,\"currency\":\"ZMW\",\"outcome\":\"PENDING\","
                + "\"reason\":\"keyed against the wrong order, Mary Banda 0977000111\",\"voidedBy\":\"user:kc-maya\",\"voidedAt\":\"2026-11-09T07:31:00Z\"}",
                justNow());
    }

    private void payment(String type, String body, String recordedAt) {
        paymentEvents.accept(Relayed.paymentEvent(UUID.randomUUID(), type, confirmationId, ++seq, body.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.RECORDED_AT, recordedAt).build());
    }

    private void orderPaid() {
        order("order.paid", "{\"orderId\":\"" + orderId + "\",\"keycloakId\":\"kc-alice\",\"previousStatus\":\"PAYMENT_SUBMITTED\",\"newStatus\":\"PAID\","
                + "\"total\":180.00,\"currency\":\"ZMW\",\"confirmationId\":\"" + confirmationId + "\",\"receiptNumber\":\"OR-000123\"}", "2");
    }

    private void orderUnsettled(String reason) {
        order("order.payment_unsettled", "{\"confirmationId\":\"" + confirmationId + "\",\"orderId\":\"" + orderId + "\",\"reason\":\"" + reason
                + "\",\"currency\":\"ZMW\"}", "1");
    }

    private void claim() {
        order("order.payment_submitted", "{\"claimId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + orderId + "\",\"method\":\"MTN\","
                + "\"referenceNormalised\":\"MP2411ABC\",\"submittedAt\":\"2026-11-09T07:30:00Z\",\"holdExpiresAt\":\""
                + START.plus(Duration.ofHours(10)) + "\"}", "1");
    }

    private void order(String type, String body, String version) {
        orderEvents.accept(Relayed.orderEvent(UUID.randomUUID(), type, orderId, ++seq, body.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.RECORDED_AT, justNow()).setHeader(EventHeaders.SCHEMA_VERSION, version).build());
    }

    private List<Map<String, Object>> alerts(String kind) {
        return jdbc.queryForList("SELECT * FROM staff_alert WHERE kind = ? ORDER BY raised_at", kind);
    }

    private Map<String, Object> stuck() {
        return alerts("SETTLEMENT_STUCK").getFirst();
    }

    private List<Map<String, Object>> rows(String kind, String notice) {
        return jdbc.queryForList("""
                SELECT m.* FROM notification_log m JOIN staff_alert a ON a.id = m.alert_id
                 WHERE a.kind = ? AND m.alert_notice = ? ORDER BY m.alert_tell_no, m.staff_to""", kind, notice);
    }

    private static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    // ---- two consumers, one order of locks ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("order's 'paid' takes the confirmation's row BEFORE it touches the claim alert, as payment's facts do: two consumers that lock in one order cannot deadlock")
    void theOrderConsumerLocksTheConfirmationBeforeTheClaimAlert() throws Exception {
        claim();
        confirmed(1);
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // A fact from payment for this confirmation is being applied: its transaction holds the confirmation's row.
            java.util.concurrent.Future<?> paymentSide = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.query("SELECT 1 FROM confirmation_watch WHERE confirmation_id = ? FOR UPDATE", row -> { }, confirmationId);
                held.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<?> orderSide = pool.submit(this::orderPaid);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class)).as("order's side is waiting").isEqualTo(1);

            // While order's side waits for the confirmation it must not be holding the claim alert, which payment's side takes next.
            org.assertj.core.api.Assertions.assertThatCode(() -> tx.executeWithoutResult(status -> jdbc.query(
                    "SELECT 1 FROM staff_alert WHERE kind = 'PAYMENT_TO_CONFIRM' AND subject_id = ? FOR UPDATE NOWAIT", row -> { }, orderId.toString())))
                    .as("the claim alert is free while order's 'paid' waits for the confirmation").doesNotThrowAnyException();

            release.countDown();
            paymentSide.get(20, java.util.concurrent.TimeUnit.SECONDS);
            orderSide.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(alerts("PAYMENT_TO_CONFIRM")).singleElement().satisfies(alert ->
                    assertThat(alert).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "PAID"));
            assertThat(jdbc.queryForMap("SELECT paid_at FROM confirmation_watch WHERE confirmation_id = ?", confirmationId).get("paid_at")).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ---- stuck -----------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the first announcement of recorded money raises nothing: order has had no time to answer")
    void theFirstAnnouncementRaisesNothing() {
        confirmed(1);

        assertThat(alerts("SETTLEMENT_STUCK")).isEmpty();
        Map<String, Object> seen = jdbc.queryForMap("SELECT * FROM confirmation_watch WHERE confirmation_id = ?", confirmationId);
        assertThat(seen).containsEntry("order_id", orderId).containsEntry("method", "MTN").containsEntry("currency", "ZMW")
                .containsEntry("highest_attempt", 1);
        assertThat(seen.get("gross").toString()).isEqualTo("180.00");
        assertThat(at(seen.get("first_seen_at"))).as("the fact's own time, not the moment it was read").isEqualTo(START.minusSeconds(1));
    }

    @Test
    @DisplayName("a second announcement with no answer tells staff once, a minute later, with the amount, the method and what to look at")
    void aSecondAnnouncementUnansweredTellsStaffOnce() {
        confirmed(1);
        confirmed(2);
        confirmed(3);

        assertThat(alerts("SETTLEMENT_STUCK")).hasSize(1);
        Map<String, Object> alert = stuck();
        assertThat(alert).containsEntry("state", "OPEN").containsEntry("subject_id", confirmationId.toString()).containsEntry("order_id", orderId);
        assertThat(alert.get("summary")).isEqualTo("A payment was recorded but the sale has not completed - order " + code);
        assertThat(alert.get("detail")).isEqualTo("A payment of K180.00 by MTN mobile money was recorded for order " + code + ", and the order is "
                + "still not marked as paid. Please open the Settlements list. If it says the sale is waiting for a box, receive that box into "
                + "stock and the sale will finish by itself. If it says anything else, the system needs attention.");
        assertThat(at(alert.get("remind_at"))).isEqualTo(START.plus(Duration.ofHours(5)));
        assertThat(at(alert.get("closes_at"))).isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(rows("SETTLEMENT_STUCK", "FIRST")).hasSize(2).allSatisfy(row ->
                assertThat(at(row.get("next_attempt_at"))).as("not at once: an answer may be seconds behind").isEqualTo(START.plus(Duration.ofMinutes(1))));
    }

    @Test
    @DisplayName("when the answer is only seconds behind the alarm, nobody is emailed: not the alarm, and no 'sorted' for an alarm nobody got")
    void anAnswerSecondsBehindTheAlarmMailsNobody() {
        confirmed(2);
        orderPaid();
        clock.advance(Duration.ofMinutes(2));
        dispatcher().run();
        dispatcher().run();

        assertThat(stuck()).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "PAID");
        assertThat(rows("SETTLEMENT_STUCK", "FIRST")).allSatisfy(row -> assertThat(row).containsEntry("status_reason", "ALERT_RESOLVED"));
        assertThat(rows("SETTLEMENT_STUCK", "SORTED")).isEmpty();
        assertThat(transport.handedOver()).extracting(Email::subject).noneMatch(subject -> subject.contains("sale has not completed"));
    }

    @Test
    @DisplayName("an answer that arrived first means no alarm at all, whichever topic it came from")
    void anAnswerThatArrivedFirstMeansNoAlert() {
        orderPaid();
        confirmed(2);
        confirmed(3);

        assertThat(alerts("SETTLEMENT_STUCK")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT paid_at IS NOT NULL FROM confirmation_watch WHERE confirmation_id = ?", Boolean.class, confirmationId)).isTrue();
    }

    @Test
    @DisplayName("once staff were told, the answer is told too, once: 'sorted', with what became of the money")
    void sortedIsSaidOnceWhenANoticeWentOut() {
        confirmed(2);
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();
        assertThat(rows("SETTLEMENT_STUCK", "FIRST")).allSatisfy(row -> assertThat(row).containsEntry("status", "SENT"));

        held("PIECE_SOLD");
        voided();
        orderUnsettled("PIECE_SOLD");

        assertThat(stuck()).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "UNSETTLED");
        List<Map<String, Object>> sorted = rows("SETTLEMENT_STUCK", "SORTED");
        assertThat(sorted).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("subject")).isEqualTo("Sorted - order " + code);
            assertThat(row.get("body")).isEqualTo("The payment of K180.00 for order " + code + " could not pay the sale (a piece had already "
                    + "been sold). The money is being kept for the customer.");
            assertThat(at(row.get("next_attempt_at"))).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));
        });

        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();
        assertThat(rows("SETTLEMENT_STUCK", "SORTED")).as("it is sent although the alert has ended: that is what it is for")
                .allSatisfy(row -> assertThat(row).containsEntry("status", "SENT"));
    }

    @Test
    @DisplayName("still open after five hours: told once more; then once a day; the support line is added when the email is built, not stored")
    void stillOpenIsRemindedAfterFiveHoursThenDaily() {
        confirmed(2);
        clock.advance(Duration.ofMinutes(1));
        dispatcher("Your system support, 0211 000 000.").run();
        assertThat(transport.handedOver()).allSatisfy(email -> assertThat(email.body()).endsWith("the system needs attention. Your system support, 0211 000 000."));

        clock.set(START.plus(Duration.ofHours(5)));
        dispatcher().run();
        List<Map<String, Object>> reminders = rows("SETTLEMENT_STUCK", "REMINDER");
        assertThat(reminders).hasSize(2).allSatisfy(row -> {
            assertThat(row).containsEntry("status", "SENT").containsEntry("alert_tell_no", 1);
            assertThat(row.get("subject")).isEqualTo("Still not completed - order " + code);
            assertThat(row.get("body")).as("the stored words carry no support line").isEqualTo("The payment of K180.00 by MTN mobile money for order "
                    + code + " has still not completed the sale. The customer has paid and cannot be served until this is fixed. Please open "
                    + "the Settlements list.");
        });
        assertThat(transport.handedOver().getLast().body()).endsWith("Please open the Settlements list. Please contact the person who looks after your system.");
        assertThat(at(stuck().get("remind_at"))).isEqualTo(START.plus(Duration.ofHours(29)));

        clock.set(START.plus(Duration.ofHours(29)));
        dispatcher().run();
        assertThat(rows("SETTLEMENT_STUCK", "REMINDER")).hasSize(4);
        assertThat(at(stuck().get("remind_at"))).isEqualTo(START.plus(Duration.ofHours(53)));
    }

    @Test
    @DisplayName("an answer that arrives right after an alarm was taken leaves the alarm to go out: staff never get 'sorted' for an alarm that was held back")
    void anAnswerRightAfterTheClaimLeavesTheAlarmToGoOut() {
        confirmed(2);
        clock.advance(Duration.ofMinutes(1));
        java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
        MessageStore answeredRightAfterTheClaim = new MessageStore(jdbc) {
            @Override
            public java.util.OptionalInt claim(UUID id, Instant now, Duration lease) {
                java.util.OptionalInt taken = super.claim(id, now, lease);
                if (taken.isPresent() && answered.compareAndSet(false, true)) {
                    // The sale completes in the instant after the row was taken: the answer sees the try and writes "sorted".
                    orderPaid();
                }
                return taken;
            }
        };
        EmailSetup setup = new EmailSetup(Mode.CATCHER, null, MONEY, List.of("alerts-cost@oriah.local"), null, false);

        new MessageDispatcher(answeredRightAfterTheClaim, contacts, orderRefs, health, new StaffAlerts(jdbc, transactions, setup, settings), watch, transport,
                setup, settings, clock).run();

        assertThat(rows("SETTLEMENT_STUCK", "SORTED")).as("the answer saw a try and says 'sorted'").hasSize(2);
        assertThat(rows("SETTLEMENT_STUCK", "FIRST")).filteredOn(row -> "SENT".equals(row.get("status")))
                .as("so the alarm it answers went out").hasSize(1);
    }

    @Test
    @DisplayName("an answer that loses the race with the clock's close still gives the alert its real ending: no 'we never heard' for a sale that completed")
    void anAnswerThatRacesTheClocksCloseStillRelabelsTheAlert() {
        confirmed(2);
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();
        EmailSetup setup = new EmailSetup(Mode.CATCHER, null, MONEY, List.of("alerts-cost@oriah.local"), null, false);
        StaffAlerts closedInBetween = new StaffAlerts(jdbc, transactions, setup, settings) {
            @Override
            public boolean resolve(StaffAlerts.Kind kind, String subjectId, String how, Instant now) {
                if (kind == StaffAlerts.Kind.SETTLEMENT_STUCK) {
                    // The dispatcher's clock closes the alert between the answer's look at it (it read OPEN) and the answer's own UPDATE.
                    jdbc.update("""
                            UPDATE staff_alert SET state = 'RESOLVED', resolved_how = 'BY_CLOCK', resolved_at = ?, remind_at = NULL
                             WHERE kind = 'SETTLEMENT_STUCK' AND state = 'OPEN'""", Timestamp.from(now));
                }
                return super.resolve(kind, subjectId, how, now);
            }
        };

        new ConfirmationWatch(jdbc, closedInBetween, orderRefs, settings).paid(confirmationId, orderId, clock.instant().minusSeconds(1), clock.instant());

        assertThat(stuck()).as("the answer is the alert's real ending, whoever closed it first").containsEntry("state", "RESOLVED")
                .containsEntry("resolved_how", "PAID");
        dispatcher().run();
        assertThat(rows("SETTLEMENT_STUCK", "CLOSING")).as("nothing is 'never answered' about it").isEmpty();
        assertThat(rows("SETTLEMENT_STUCK", "SORTED")).hasSize(2);
    }

    @Test
    @DisplayName("after days with the service away, staff get ONE 'still not completed', not one for every day that was missed")
    void afterDaysAwayStaffGetOneReminderNotOnePerMissedDay() {
        confirmed(2);
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();

        // The first reminder was due after five hours; nothing ran for three days and two hours after that.
        clock.set(START.plus(Duration.ofHours(5 + 74)));
        dispatcher().run();
        dispatcher().run();
        dispatcher().run();

        assertThat(rows("SETTLEMENT_STUCK", "REMINDER")).as("one reminder, to each of the two mailboxes").hasSize(2);
        assertThat(at(stuck().get("remind_at"))).as("the next one keeps the daily hour and lies ahead").isEqualTo(START.plus(Duration.ofHours(5 + 96)));
    }

    @Test
    @DisplayName("the last note of an alert that closed by the clock is written even if the service stopped right after closing it")
    void theLastNoteIsWrittenEvenIfTheServiceStoppedAfterTheClose() {
        confirmed(2);
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();
        clock.set(START.plus(Duration.ofDays(14)));
        // The close was committed and the service stopped before the note was written: what an earlier pass left behind.
        jdbc.update("UPDATE staff_alert SET state = 'RESOLVED', resolved_how = 'BY_CLOCK', resolved_at = ?, remind_at = NULL WHERE kind = 'SETTLEMENT_STUCK'",
                Timestamp.from(clock.instant()));

        dispatcher().run();
        dispatcher().run();

        assertThat(rows("SETTLEMENT_STUCK", "CLOSING")).as("said once, to each of the two mailboxes").hasSize(2)
                .allSatisfy(row -> assertThat(row.get("subject")).isEqualTo("We have stopped reminding you - order " + code));
    }

    @Test
    @DisplayName("a sale still waiting after two weeks gets a last note and is closed as never answered; an answer that comes even later still says 'sorted'")
    void aSaleStillWaitingAfterTwoWeeksGetsALastNote() {
        confirmed(2);
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();

        clock.set(START.plus(Duration.ofDays(14)));
        dispatcher().run();

        assertThat(stuck()).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "BY_CLOCK");
        assertThat(rows("SETTLEMENT_STUCK", "CLOSING")).hasSize(2).allSatisfy(row -> {
            assertThat(row).containsEntry("status", "SENT");
            assertThat(row.get("subject")).isEqualTo("We have stopped reminding you - order " + code);
            assertThat(row.get("body")).isEqualTo("We have stopped reminding you about the payment of K180.00 by MTN mobile money for order " + code
                    + ". We never heard that the sale was completed. Please open the Settlements list.");
        });

        clock.advance(Duration.ofDays(3));
        orderPaid();
        assertThat(stuck()).as("it leaves the 'never answered' list").containsEntry("resolved_how", "PAID");
        assertThat(rows("SETTLEMENT_STUCK", "SORTED")).hasSize(2).allSatisfy(row ->
                assertThat(row.get("body")).isEqualTo("The payment of K180.00 for order OR-000123 has now completed the sale. There is nothing more to do."));
    }

    @Test
    @DisplayName("an announcement read long after the fact updates what is known and tells nobody: history read late looks as old as it is")
    void anOldAnnouncementUpdatesTheRowAndTellsNobody() {
        confirmed(2, "2026-11-09T06:00:00Z");
        confirmed(7, "2026-11-09T06:50:00Z");

        assertThat(alerts("SETTLEMENT_STUCK")).isEmpty();
        assertThat(jdbc.queryForMap("SELECT highest_attempt, first_seen_at FROM confirmation_watch WHERE confirmation_id = ?", confirmationId))
                .containsEntry("highest_attempt", 7).containsEntry("first_seen_at", Timestamp.from(Instant.parse("2026-11-09T06:00:00Z")));
    }

    // ---- a cancelled record that paid a sale anyway ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a payment record cancelled and a sale paid by it, in either order, is an alert; a cancelled record that paid nothing is not")
    void voidAfterSale() {
        voided();
        assertThat(alerts("VOID_AFTER_SALE")).as("a void of money that never paid").isEmpty();
        orderPaid();

        Map<String, Object> alert = alerts("VOID_AFTER_SALE").getFirst();
        assertThat(alert).containsEntry("state", "OPEN").containsEntry("subject_id", confirmationId.toString());
        assertThat(alert.get("summary")).isEqualTo("A cancelled payment record paid a sale anyway - order OR-000123");
        assertThat(alert.get("detail")).isEqualTo("The payment record of K180.00 by MTN mobile money for order OR-000123 was cancelled (voided), but "
                + "the sale had already gone through, so the order is marked as paid and its pieces are sold. Please check whether this money "
                + "really arrived, and put it right with the customer and in your books.");
        assertThat(at(alert.get("closes_at"))).isEqualTo(START.plus(Duration.ofDays(7)));
        assertThat(rows("VOID_AFTER_SALE", "FIRST")).hasSize(2);
        assertThat(alert.get("detail").toString()).doesNotContain("Mary").doesNotContain("0977000111").doesNotContain("wrong order");
    }

    @Test
    @DisplayName("the other order: the sale is paid, then its payment record is cancelled")
    void aPaidSaleThenAVoidRaisesItToo() {
        confirmed(1);
        orderPaid();
        voided();
        voided();

        assertThat(alerts("VOID_AFTER_SALE")).hasSize(1);
    }

    // ---- the claim alert and recorded money --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("once money is recorded for a claimed order, the claim alert steps aside (no reminder to 'confirm or reject'); a paid order then reads PAID")
    void recordedMoneyTakesTheClaimAlertOver() {
        claim();
        confirmed(1);

        Map<String, Object> claimAlert = alerts("PAYMENT_TO_CONFIRM").getFirst();
        assertThat(claimAlert).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "MONEY_RECORDED");

        orderPaid();
        assertThat(alerts("PAYMENT_TO_CONFIRM")).singleElement().satisfies(alert -> assertThat(alert).containsEntry("resolved_how", "PAID"));
    }

    @Test
    @DisplayName("if that money then cannot pay the order (a wrong amount) while the hold still runs, the claim is open again, with no second first email")
    void aWrongAmountReopensTheClaimAlertWithoutAFirstEmail() {
        claim();
        confirmed(1);
        orderUnsettled("WRONG_AMOUNT");

        List<Map<String, Object>> claimAlerts = alerts("PAYMENT_TO_CONFIRM");
        assertThat(claimAlerts).extracting(alert -> alert.get("state")).containsExactly("RESOLVED", "OPEN");
        assertThat(at(claimAlerts.get(1).get("remind_at"))).as("its reminder is still owed").isEqualTo(START.plus(Duration.ofHours(7)));
        assertThat(rows("PAYMENT_TO_CONFIRM", "FIRST")).as("staff were told of this claim once").hasSize(2);

        orderUnsettled("WRONG_AMOUNT");
        assertThat(alerts("PAYMENT_TO_CONFIRM")).as("the same fact again reopens nothing more").hasSize(2);
    }

    @Test
    @DisplayName("nothing reopens once the hold has ended, or while other money for the order is still waiting for its answer")
    void nothingReopensOnceTheHoldHasEnded() {
        claim();
        confirmed(1);
        clock.set(START.plus(Duration.ofHours(11)));
        voided();

        assertThat(alerts("PAYMENT_TO_CONFIRM")).hasSize(1);
    }

    @Test
    @DisplayName("a cancelled record does not reopen the claim while other money for the order still waits for its answer, or after other money paid it")
    void otherMoneyForTheOrderKeepsTheClaimClosed() {
        claim();
        confirmed(1);
        UUID otherMoney = UUID.randomUUID();
        Instant fact = clock.instant().minusSeconds(1);
        watch.confirmed(otherMoney, orderId, "MTN", new java.math.BigDecimal("180.00"), "ZMW", 1, fact, clock.instant());

        voided();
        assertThat(alerts("PAYMENT_TO_CONFIRM")).as("the second record is still waiting for order's answer").hasSize(1);

        watch.paid(otherMoney, orderId, fact, clock.instant());
        held("ALREADY_PAID");
        assertThat(alerts("PAYMENT_TO_CONFIRM")).as("the order is paid by the second record").hasSize(1);
    }

    // ---- what is read, refused and ignored -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a rejected claim and the card path's events are applied and ignored; a payment type nobody declared is refused; so is a body about another confirmation")
    void whatIsIgnoredAndWhatIsRefused() {
        for (String ignored : List.of("payment.succeeded", "payment.failed")) {
            UUID eventId = UUID.randomUUID();
            paymentEvents.accept(Relayed.paymentEvent(eventId, ignored, confirmationId, 1, "{}".getBytes(StandardCharsets.UTF_8))
                    .setHeader(EventHeaders.AGGREGATE_TYPE, "payment_intent").build());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer = 'notification' AND event_id = ?", Long.class, eventId))
                    .as(ignored).isEqualTo(1);
        }
        paymentEvents.accept(Relayed.paymentEvent(UUID.randomUUID(), "payment.rejected", confirmationId, 1, "{}".getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.AGGREGATE_TYPE, "payment_claim").build());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM confirmation_watch", Long.class)).isZero();

        assertThatThrownBy(() -> paymentEvents.accept(Relayed.paymentEvent(UUID.randomUUID(), "payment.refunded", confirmationId, 1,
                "{}".getBytes(StandardCharsets.UTF_8)).build())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payment.refunded");
        assertThatThrownBy(() -> paymentEvents.accept(Relayed.paymentEvent(UUID.randomUUID(), "payment.confirmed", UUID.randomUUID(), 1,
                ("{\"confirmationId\":\"" + confirmationId + "\",\"orderId\":\"" + orderId + "\",\"attempt\":2}").getBytes(StandardCharsets.UTF_8)).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(alerts("SETTLEMENT_STUCK")).isEmpty();
    }

    @Test
    @DisplayName("nothing personal is read from a payment event: no reference, no staff member, no typed reason")
    void nothingPersonalIsRead() {
        for (Class<?> fact : PaymentEventConsumer.class.getDeclaredClasses()) {
            if (fact.isRecord()) {
                assertThat(Arrays.stream(fact.getRecordComponents()).map(component -> component.getName()))
                        .as(fact.getSimpleName()).doesNotContain("reference", "confirmedBy", "voidedBy", "backdateReason", "arrivedAt");
            }
        }
        voided();
        orderPaid();
        assertThat(jdbc.queryForList("SELECT detail || ' ' || summary FROM staff_alert", String.class))
                .allSatisfy(words -> assertThat(words).doesNotContain("MP2411ABC").doesNotContain("kc-maya").doesNotContain("Mary"));
    }

    @Test
    @DisplayName("payment's topic is read through the Inbox like order's: its own dead-letter topic, three deliveries, the same two failures that are never retried, from the beginning")
    void thePaymentBinding() {
        assertThat(events.inbox().topics()).containsExactly("oriah.order", "oriah.payment");
        var binding = bindings.getBindingProperties("paymentEvents-in-0");
        assertThat(binding.getDestination()).isEqualTo("oriah.payment");
        assertThat(binding.getGroup()).isEqualTo("notification");
        assertThat(binding.getConsumer().getMaxAttempts()).isEqualTo(3);
        assertThat(binding.getConsumer().isUseNativeDecoding()).isFalse();
        assertThat(binding.getConsumer().getRetryableExceptions()).containsOnlyKeys(IllegalArgumentException.class, JsonProcessingException.class)
                .containsEntry(IllegalArgumentException.class, false).containsEntry(JsonProcessingException.class, false);
    }
}
