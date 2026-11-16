package com.oriah.notification.delivery;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.orders.OrderRefs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one job that sends. Each pass: end the messages whose time has passed, find the ones that may go now, and for each one decide, take it,
 * hand it to the mail server with NO database transaction open, and write down what happened.
 *
 * <p>These tests use a real Postgres, a clock that stands still until moved, and a transport that answers what a test tells it to.
 */
@ServiceTest
@ExtendWith(OutputCaptureExtension.class)
class MessageDispatcherTest {

    private static final Instant START = MutableClock.START;

    @Autowired
    private MessageStore store;
    @Autowired
    private ContactBook contacts;
    @Autowired
    private OrderRefs orderRefs;
    @Autowired
    private DispatchHealth health;
    @Autowired
    private com.oriah.notification.alerts.StaffAlerts alerts;
    @Autowired
    private com.oriah.notification.alerts.ConfirmationWatch watch;
    @Autowired
    private NotificationSettings settings;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final RecordingTransport transport = new RecordingTransport();
    private final AtomicInteger written = new AtomicInteger();

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------------

    private static EmailSetup setup(Mode mode, boolean paused) {
        return new EmailSetup(mode, null, List.of(), List.of(), null, paused);
    }

    private MessageDispatcher dispatcher() {
        return dispatcher(setup(Mode.CATCHER, false), store);
    }

    private MessageDispatcher dispatcher(EmailSetup setup, MessageStore store) {
        return new MessageDispatcher(store, contacts, orderRefs, health, alerts, watch, transport, setup, settings, clock);
    }

    /** A customer's message waiting to be emailed, as the consumer writes it: due now, worth sending for six hours. */
    private UUID waiting(String owner, UUID orderId, String eventType, long seq) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, recorded_at, recipient_kind, status,
                                              status_at, next_attempt_at, send_before, event_id, event_seq, event_recorded_at)
                VALUES (?, ?, ?, ?, 'email', ?, ?, ?, 'SHOPPER', 'RECORDED', ?, ?, ?, ?, ?, ?)""",
                id, orderId, owner, eventType, "Subject of " + eventType, "The stored words of " + eventType + " K180.00 OR-000123",
                // Each row a microsecond after the last, so "the oldest first" is the order a test wrote them in.
                Timestamp.from(clock.instant().plusNanos(1_000L * written.incrementAndGet())), now, now,
                Timestamp.from(clock.instant().plus(Duration.ofHours(6))), UUID.randomUUID(), seq, now);
        return id;
    }

    private UUID waiting(String owner, String eventType) {
        return waiting(owner, UUID.randomUUID(), eventType, 1);
    }

    private void knows(String owner, String email, boolean verified) {
        jdbc.update("""
                INSERT INTO recipient_contact (keycloak_id, email, email_verified, token_issued_at, first_seen_at, seen_at)
                VALUES (?, ?, ?, now(), now(), now())""", owner, email, verified);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM notification_log WHERE id = ?", id);
    }

    private String status(UUID id) {
        Map<String, Object> row = row(id);
        return row.get("status") + (row.get("status_reason") == null ? "" : "/" + row.get("status_reason"));
    }

    private Instant nextTry(UUID id) {
        Timestamp next = (Timestamp) row(id).get("next_attempt_at");
        return next == null ? null : next.toInstant();
    }

    // ---- the ordinary path -----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a waiting message is handed over once and marked sent; the footer is added to the email and is not stored")
    void aRecordedMessageIsHandedOverOnceAndMarkedSent() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID orderId = UUID.randomUUID();
        UUID id = waiting("kc-alice", orderId, "order.paid", 2);

        dispatcher().run();
        dispatcher().run();

        assertThat(transport.handedOver()).singleElement().satisfies(email -> {
            assertThat(email.rowId()).isEqualTo(id);
            assertThat(email.to()).isEqualTo("alice@oriah.local");
            assertThat(email.subject()).isEqualTo("Subject of order.paid");
            assertThat(email.body()).startsWith("The stored words of order.paid K180.00 OR-000123").contains("Oriah Jewelry");
        });
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(((Timestamp) row.get("sent_at")).toInstant()).isEqualTo(START);
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("short_form")).isEqualTo(false);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("body")).as("the stored words are not changed by sending").isEqualTo("The stored words of order.paid K180.00 OR-000123");
        assertThat(transport.sawATransaction()).as("no database transaction is open while a message is being sent").isFalse();
    }

    @Test
    @DisplayName("rows that are not waiting are never handed over: website-only ones, the ones from before sending existed, words for a caller")
    void rowsThatAreNotWaitingAreNeverHandedOver() {
        knows("kc-alice", "alice@oriah.local", true);
        Timestamp now = Timestamp.from(START);
        for (String reason : List.of("BELL_ONLY", "RECORDED_BEFORE_SENDING")) {
            jdbc.update("""
                    INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, recorded_at, recipient_kind, status,
                                                  status_reason, status_at)
                    VALUES (?, ?, 'kc-alice', 'order.created', 'email', 's', 'b', ?, 'SHOPPER', 'NOT_SENT', ?, ?)""",
                    UUID.randomUUID(), UUID.randomUUID(), now, reason, now);
        }
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, event_type, channel, subject, body, recorded_at, recipient_kind, status, status_at)
                VALUES (?, ?, 'order.paid', 'by-hand', 's', 'b', ?, 'CALLER', 'BY_HAND', ?)""", UUID.randomUUID(), UUID.randomUUID(), now, now);

        clock.advance(Duration.ofDays(400));
        dispatcher().run();

        assertThat(transport.handedOver()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE status IN ('NOT_SENT', 'BY_HAND')", Long.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("with no mail server, and while all email is paused, a waiting message becomes 'not sent, sending off'; it is never sent later")
    void sendingOffDrainsTheQueue() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID off = waiting("kc-alice", "order.paid");
        dispatcher(setup(Mode.OFF, false), store).run();
        UUID paused = waiting("kc-alice", "order.paid");
        dispatcher(setup(Mode.CATCHER, true), store).run();

        assertThat(status(off)).isEqualTo("NOT_SENT/SENDING_OFF");
        assertThat(status(paused)).isEqualTo("NOT_SENT/SENDING_OFF");

        dispatcher().run();
        assertThat(transport.handedOver()).as("unpausing sends nothing from before").isEmpty();
    }

    // ---- whose address ---------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a message whose customer's address is not known yet waits without spending a try, and goes once the address is learnt")
    void waitsForAnAddressWithoutSpendingATryThenSends() {
        UUID id = waiting("kc-alice", "order.paid");

        dispatcher().run();
        assertThat(status(id)).isEqualTo("RECORDED");
        assertThat(row(id).get("attempts")).isEqualTo(0);
        assertThat(nextTry(id)).isEqualTo(START.plus(Duration.ofMinutes(1)));

        knows("kc-alice", "alice@oriah.local", true);
        dispatcher().run();
        assertThat(transport.handedOver()).as("not before its next look").isEmpty();
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();
        assertThat(status(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("an address nobody confirmed is sent the short note in place of the stored words, and the row says so")
    void anUnverifiedShopperGetsTheShortForm() {
        knows("kc-bob", "bob@oriah.local", false);
        UUID id = waiting("kc-bob", "order.paid");

        dispatcher().run();

        assertThat(transport.handedOver()).singleElement().satisfies(email -> {
            assertThat(email.subject()).isEqualTo("News about your Oriah order");
            assertThat(email.body()).startsWith("There is news about your order. Please sign in on our website to read it.")
                    .doesNotContain("K180.00").doesNotContain("OR-000123").doesNotContain("/orders/");
        });
        assertThat(status(id)).isEqualTo("SENT");
        assertThat(row(id).get("short_form")).isEqualTo(true);
        assertThat(row(id).get("body")).isEqualTo("The stored words of order.paid K180.00 OR-000123");
    }

    @Test
    @DisplayName("an address nobody confirmed is not emailed 'we could not complete your order': a stranger could cause that message for free")
    void anUnverifiedShopperIsNotMailedTheClosedOrderMessage() {
        knows("kc-bob", "bob@oriah.local", false);
        UUID id = waiting("kc-bob", "order.expired");

        dispatcher().run();

        assertThat(status(id)).isEqualTo("NOT_SENT/UNVERIFIED_ADDRESS");
        assertThat(transport.handedOver()).isEmpty();
    }

    @Test
    @DisplayName("on a stack that is not production, an address that could be a real mailbox is never handed over")
    void notLiveARealLookingAddressIsNeverHandedOver() {
        knows("kc-nora", "nora@example.com", true);
        UUID id = waiting("kc-nora", "order.paid");

        dispatcher().run();

        assertThat(status(id)).isEqualTo("NOT_SENT/SANDBOX");
        assertThat(transport.handedOver()).isEmpty();
    }

    @Test
    @DisplayName("if the transport's own sandbox lock turns a message back, the row says 'sandbox', not 'address refused'")
    void theTransportsOwnLockIsRecordedAsSandbox() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID id = waiting("kc-alice", "order.paid");
        transport.thenAnswer(SendResult.refused(SendResult.SANDBOX));

        dispatcher().run();

        assertThat(status(id)).isEqualTo("NOT_SENT/SANDBOX");
    }

    // ---- the order of one order's messages, and messages overtaken by events --------------------------------------------------------------------

    @Test
    @DisplayName("one order's messages go in the order their events happened, and a later one waits behind an earlier one that is still waiting")
    void anOrdersMessagesGoInTheOrderOfTheirSeq() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID orderId = UUID.randomUUID();
        UUID onItsWay = waiting("kc-alice", orderId, "order.shipped", 4);
        UUID paid = waiting("kc-alice", orderId, "order.paid", 2);
        transport.thenAnswer(new SendResult(SendResult.Outcome.RECIPIENT_TRY_LATER, SendResult.RCPT_4XX));

        dispatcher().run();

        assertThat(transport.handedOver()).extracting(Email::rowId).as("only the earlier one was tried").containsExactly(paid);
        assertThat(status(onItsWay)).isEqualTo("RECORDED");
        assertThat(row(onItsWay).get("attempts")).isEqualTo(0);

        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();
        dispatcher().run();
        assertThat(transport.handedOver()).extracting(Email::rowId).containsExactly(paid, paid, onItsWay);
    }

    @Test
    @DisplayName("'ready to collect' is not sent once the order was handed over; 'on its way' is not sent once it was delivered; 'payment received' always is")
    void messagesOvertakenByEventsAreNotSent() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID collected = UUID.randomUUID();
        UUID delivered = UUID.randomUUID();
        UUID paidFirst = waiting("kc-alice", collected, "order.paid", 2);
        UUID ready = waiting("kc-alice", collected, "order.fulfilled", 3);
        bellOnly("kc-alice", collected, "order.delivered", 4);
        UUID onItsWay = waiting("kc-alice", delivered, "order.shipped", 4);
        UUID wasDelivered = waiting("kc-alice", delivered, "order.delivered", 5);

        dispatcher().run();
        dispatcher().run();

        assertThat(status(paidFirst)).isEqualTo("SENT");
        assertThat(status(ready)).isEqualTo("NOT_SENT/SUPERSEDED");
        assertThat(status(onItsWay)).isEqualTo("NOT_SENT/SUPERSEDED");
        assertThat(status(wasDelivered)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("'we could not complete your order' is not sent once the order turned out to be paid, even if the payment was only ever heard of as a repeat")
    void theClosedOrderMessageIsNotSentOnceTheOrderWasPaid() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID paidAfterAll = UUID.randomUUID();
        UUID stillEnded = UUID.randomUUID();
        UUID overtaken = waiting("kc-alice", paidAfterAll, "order.expired", 3);
        UUID stands = waiting("kc-alice", stillEnded, "order.expired", 3);
        // No "payment received" row exists for it: the first announcement never reached notification, a repeat did, and a repeat writes no row.
        orderRefs.note(paidAfterAll, "kc-alice", false, null, "OR-000124", START, START);

        dispatcher().run();

        assertThat(status(overtaken)).isEqualTo("NOT_SENT/SUPERSEDED");
        assertThat(status(stands)).isEqualTo("SENT");
    }

    private void bellOnly(String owner, UUID orderId, String eventType, long seq) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, recorded_at, recipient_kind, status,
                                              status_reason, status_at, event_id, event_seq, event_recorded_at)
                VALUES (?, ?, ?, ?, 'email', 's', 'b', ?, 'SHOPPER', 'NOT_SENT', 'BELL_ONLY', ?, ?, ?, ?)""",
                UUID.randomUUID(), orderId, owner, eventType, now, now, UUID.randomUUID(), seq, now);
    }

    // ---- failures --------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a mailbox that cannot take mail just now is tried again after 1, 5, 15 and 60 minutes, then every hour; the pass goes on and mail health is untouched")
    void aDeferredRecipientBacksOffAndTouchesNothingElse() {
        knows("kc-alice", "alice@oriah.local", true);
        knows("kc-bob", "bob@oriah.local", true);
        UUID slow = waiting("kc-alice", "order.paid");
        UUID fine = waiting("kc-bob", "order.paid");
        SendResult later = SendResult.recipientTryLater();
        transport.thenAnswer(later);

        dispatcher().run();

        assertThat(status(fine)).as("the pass went on to the next message").isEqualTo("SENT");
        assertThat(health.state().failingSince()).isNull();
        assertThat(row(slow).get("last_error")).isEqualTo("RCPT_4XX");
        Instant tried = START;
        for (Duration wait : List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(1))) {
            assertThat(nextTry(slow)).isEqualTo(tried.plus(wait));
            clock.set(tried.plus(wait));
            tried = clock.instant();
            transport.thenAnswer(later);
            dispatcher().run();
        }
        assertThat(row(slow).get("attempts")).isEqualTo(6);
        assertThat(status(slow)).isEqualTo("RECORDED");
    }

    @Test
    @DisplayName("when the mail server itself fails, the pass ends, no message fails, and nothing is handed over for a minute, then two, four ... up to fifteen")
    void aServerFailureEndsThePassAndQuietensTheDispatcher() {
        knows("kc-alice", "alice@oriah.local", true);
        knows("kc-bob", "bob@oriah.local", true);
        UUID first = waiting("kc-alice", "order.paid");
        UUID second = waiting("kc-bob", "order.paid");
        transport.thenAnswer(SendResult.serverTryLater(SendResult.AUTH));

        dispatcher().run();

        assertThat(transport.handedOver()).as("the second message was not tried: it would fail the same way").hasSize(1);
        assertThat(status(first)).isEqualTo("RECORDED");
        assertThat(row(first).get("last_error")).isEqualTo("AUTH");
        assertThat(status(second)).isEqualTo("RECORDED");
        assertThat(row(second).get("attempts")).isEqualTo(0);
        assertThat(health.state().failingSince()).isEqualTo(START);
        assertThat(health.state().quietUntil()).isEqualTo(START.plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofSeconds(59));
        dispatcher().run();
        assertThat(transport.handedOver()).as("quiet for the whole minute").hasSize(1);

        // Each failure in a row doubles the quiet time, up to fifteen minutes.
        Instant at = START;
        for (Duration quiet : List.of(Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8), Duration.ofMinutes(15), Duration.ofMinutes(15))) {
            at = health.state().quietUntil();
            clock.set(at);
            transport.thenAnswer(SendResult.serverTryLater(SendResult.CONNECT));
            dispatcher().run();
            assertThat(health.state().quietUntil()).isEqualTo(at.plus(quiet));
        }
        assertThat(health.state().failingSince()).as("it has been failing since the first failure").isEqualTo(START);

        clock.set(health.state().quietUntil());
        dispatcher().run();
        assertThat(status(second)).isEqualTo("SENT");
        assertThat(health.state()).as("the first message the server takes clears it").isEqualTo(new DispatchHealth.State(null, 0, null));
    }

    @Test
    @DisplayName("a refused address fails that message at once, and only that message")
    void aRefusedAddressFailsAtOnce() {
        knows("kc-alice", "alice@oriah.local", true);
        knows("kc-bob", "bob@oriah.local", true);
        UUID refused = waiting("kc-alice", "order.paid");
        UUID fine = waiting("kc-bob", "order.paid");
        transport.thenAnswer(SendResult.refused(SendResult.ADDRESS_REFUSED));

        dispatcher().run();

        assertThat(status(refused)).isEqualTo("FAILED/ADDRESS_REFUSED");
        assertThat(status(fine)).isEqualTo("SENT");
        assertThat(health.state().failingSince()).isNull();
    }

    @Test
    @DisplayName("a send whose answer never came is tried once more, an hour later at the earliest, and then ends as 'outcome unknown': never a copy on every rung")
    void anUncertainSendIsRetriedAtMostOnce() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID id = waiting("kc-alice", "order.paid");
        transport.thenAnswer(SendResult.uncertain());

        dispatcher().run();

        assertThat(status(id)).isEqualTo("RECORDED");
        assertThat(row(id).get("maybe_handed_over")).isEqualTo(true);
        assertThat(nextTry(id)).as("not on the short ladder: at the longest wait").isEqualTo(START.plus(Duration.ofHours(1)));
        assertThat(health.state().failingSince()).as("an answer that never came says nothing about the server").isNull();

        clock.advance(Duration.ofMinutes(30));
        dispatcher().run();
        assertThat(transport.handedOver()).hasSize(1);

        clock.advance(Duration.ofMinutes(30));
        transport.thenAnswer(SendResult.uncertain());
        dispatcher().run();
        assertThat(transport.handedOver()).hasSize(2);
        assertThat(status(id)).isEqualTo("FAILED/OUTCOME_UNKNOWN");
    }

    @Test
    @DisplayName("two unanswered sends in one pass end the pass: something is wrong, though nobody can say what")
    void twoUncertainSendsEndThePass() {
        for (String owner : List.of("kc-a", "kc-b", "kc-c")) {
            knows(owner, owner + "@oriah.local", true);
            waiting(owner, "order.paid");
        }
        transport.thenAnswer(SendResult.uncertain(), SendResult.uncertain());

        dispatcher().run();

        assertThat(transport.handedOver()).hasSize(2);
    }

    // ---- the deadline ------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("past its deadline a message ends: given up if it was tried, 'no address' if its customer was never seen, 'too late' otherwise, 'outcome unknown' if it may have arrived")
    void pastItsDeadlineAMessageEnds() {
        knows("kc-alice", "alice@oriah.local", true);
        knows("kc-carol", "carol@oriah.local", true);
        UUID tried = waiting("kc-alice", "order.paid");
        UUID maybe = waiting("kc-carol", "order.paid");
        transport.thenAnswer(SendResult.recipientTryLater(), SendResult.uncertain());
        dispatcher().run();
        UUID neverSeen = waiting("kc-nobody", "order.paid");
        // Never tried although its customer is known: it only became due after its deadline (the service was away).
        UUID tooLate = waiting("kc-alice", "order.paid");
        jdbc.update("UPDATE notification_log SET next_attempt_at = ? WHERE id = ?", Timestamp.from(START.plus(Duration.ofHours(7))), tooLate);

        clock.advance(Duration.ofHours(6));
        dispatcher().run();

        assertThat(status(tried)).isEqualTo("FAILED/GAVE_UP");
        assertThat(status(maybe)).isEqualTo("FAILED/OUTCOME_UNKNOWN");
        assertThat(status(neverSeen)).isEqualTo("NOT_SENT/NO_ADDRESS");
        assertThat(status(tooLate)).isEqualTo("NOT_SENT/TOO_LATE");
        assertThat(transport.handedOver()).hasSize(2);
    }

    // ---- two dispatchers, and outcomes that arrive late ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("two dispatchers never send the same message, even while the first one's send hangs; and nothing ends a message whose send is in flight")
    void twoDispatchersNeverSendTheSameRowWhileASendHangs() throws Exception {
        knows("kc-alice", "alice@oriah.local", true);
        UUID id = waiting("kc-alice", "order.paid");
        jdbc.update("UPDATE notification_log SET send_before = ? WHERE id = ?", Timestamp.from(START.plus(Duration.ofSeconds(30))), id);
        transport.holdSends();
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> dispatcher().run());
        assertThat(transport.aSendIsInFlight()).isTrue();

        // While it hangs: another dispatcher passes, a minute later, past the message's deadline but inside the first one's lease.
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();
        assertThat(transport.handedOver()).as("the second dispatcher did not send it").hasSize(1);
        assertThat(status(id)).as("and did not end it: its send may still succeed").isEqualTo("RECORDED");

        transport.letGo();
        first.get(20, TimeUnit.SECONDS);
        assertThat(status(id)).isEqualTo("SENT");
        assertThat(transport.sawATransaction()).isFalse();
    }

    @Test
    @DisplayName("an outcome that arrives after somebody else took the row changes nothing")
    void aLateOutcomeOfAnOlderClaimChangesNothing() {
        UUID id = waiting("kc-alice", "order.paid");
        int older = store.claim(id, START, Duration.ofMinutes(3)).orElseThrow();
        clock.advance(Duration.ofMinutes(4));
        int newer = store.claim(id, clock.instant(), Duration.ofMinutes(3)).orElseThrow();

        assertThat(store.markSent(id, older, clock.instant(), false)).isFalse();
        assertThat(store.fail(id, older, com.oriah.notification.model.StatusReason.ADDRESS_REFUSED, "ADDRESS_REFUSED", clock.instant())).isFalse();
        assertThat(status(id)).isEqualTo("RECORDED");
        assertThat(store.claim(id, clock.instant(), Duration.ofMinutes(3))).as("it is out of reach while the newer claim's lease runs").isEmpty();
        assertThat(store.markSent(id, newer, clock.instant(), false)).isTrue();
        assertThat(status(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a message the server took but whose record could not be written is recorded on the next pass and never handed over again")
    void anAcceptedSendWhoseRecordFailedIsRecordedLaterAndNotSentAgain() {
        knows("kc-alice", "alice@oriah.local", true);
        UUID id = waiting("kc-alice", "order.paid");
        AtomicInteger failures = new AtomicInteger(10);
        MessageStore databaseAwayAfterTheSend = new MessageStore(jdbc) {
            @Override
            public boolean markSent(UUID row, int claimed, Instant now, boolean shortForm) {
                if (failures.getAndDecrement() > 0) {
                    throw new org.springframework.dao.TransientDataAccessResourceException("the database is restarting");
                }
                return super.markSent(row, claimed, now, shortForm);
            }
        };
        MessageDispatcher dispatcher = dispatcher(setup(Mode.CATCHER, false), databaseAwayAfterTheSend);

        dispatcher.run();
        assertThat(status(id)).as("the server took it, the record could not be written").isEqualTo("RECORDED");

        failures.set(0);
        clock.advance(Duration.ofMinutes(4));
        dispatcher.run();

        assertThat(status(id)).isEqualTo("SENT");
        assertThat(transport.handedOver()).as("it was handed over once").hasSize(1);
    }

    @Test
    @DisplayName("a message that cannot be handled is logged by its id and the next one is still sent")
    void aRowThatCannotBeHandledIsLoggedAndTheNextIsSent(CapturedOutput output) {
        knows("kc-alice", "alice@oriah.local", true);
        knows("kc-bob", "bob@oriah.local", true);
        UUID broken = waiting("kc-alice", "order.paid");
        UUID fine = waiting("kc-bob", "order.paid");
        MessageStore oneRowBreaks = new MessageStore(jdbc) {
            @Override
            public OptionalInt claim(UUID row, Instant now, Duration lease) {
                if (row.equals(broken)) {
                    throw new IllegalStateException("something unexpected about alice@oriah.local");
                }
                return super.claim(row, now, lease);
            }
        };

        dispatcher(setup(Mode.CATCHER, false), oneRowBreaks).run();

        assertThat(status(fine)).isEqualTo("SENT");
        assertThat(status(broken)).isEqualTo("RECORDED");
        assertThat(output.getAll()).contains(broken.toString()).doesNotContain("alice@oriah.local");
    }

    @Test
    @DisplayName("no log line of a pass carries an address or a message's words")
    void noLogLineCarriesAnAddressOrABody(CapturedOutput output) {
        knows("kc-alice", "alice@oriah.local", true);
        knows("kc-bob", "bob@oriah.local", false);
        waiting("kc-alice", "order.paid");
        waiting("kc-bob", "order.expired");
        waiting("kc-alice", "order.paid");
        transport.thenAnswer(SendResult.refused(SendResult.ADDRESS_REFUSED), SendResult.serverTryLater(SendResult.CONNECT));

        dispatcher().run();

        assertThat(output.getAll()).doesNotContain("@oriah.local").doesNotContain("The stored words").doesNotContain("K180.00");
    }
}
