package com.oriah.notification.alerts;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.events.EventHeaders;
import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.config.TestSettings;
import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.delivery.DispatchHealth;
import com.oriah.notification.delivery.Email;
import com.oriah.notification.delivery.MessageDispatcher;
import com.oriah.notification.delivery.MessageStore;
import com.oriah.notification.delivery.RecordingTransport;
import com.oriah.notification.delivery.SendResult;
import com.oriah.notification.events.Relayed;
import com.oriah.notification.orders.OrderRefs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one alert the client asked for: a customer says they have paid, and staff must look for the money before the pieces are released.
 *
 * <p>The alert is raised at once when the claim arrives ({@code order.payment_submitted}), emailed to every MONEY mailbox, and reminded of once
 * before the hold ends. It ends when the order is paid, expires or is cancelled, or by the clock just after the hold. Its words name no amount
 * (the claim carries none). The test context's two MONEY mailboxes are {@code alerts-money@oriah.local} and {@code alerts-owner@oriah.local}.
 */
@ServiceTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentToConfirmAlertTest {

    private static final Instant START = MutableClock.START;
    /** The hold of the claims in these tests ends ten hours after the clock's start: 19:31 in Lusaka, so the reminder goes at 16:31. */
    private static final Instant HOLD_ENDS = START.plus(Duration.ofHours(10));
    private static final List<String> MONEY = List.of("alerts-money@oriah.local", "alerts-owner@oriah.local");

    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private StaffAlerts alerts;
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
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final RecordingTransport transport = new RecordingTransport();

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------------

    private static EmailSetup catcher(List<String> money) {
        return new EmailSetup(Mode.CATCHER, null, money, List.of("alerts-cost@oriah.local"), null, false);
    }

    /** A dispatcher on a stack with the catcher and the two money mailboxes; its alert store is built with the same setup, as in the service. */
    private MessageDispatcher dispatcher() {
        return dispatcher(catcher(MONEY), new StaffAlerts(jdbc, transactions, catcher(MONEY), settings));
    }

    private MessageDispatcher dispatcher(EmailSetup setup, StaffAlerts staffAlerts) {
        return new MessageDispatcher(store, contacts, orderRefs, health, staffAlerts, transport, setup, settings, clock);
    }

    private void claim(UUID orderId) {
        claim(orderId, UUID.randomUUID(), HOLD_ENDS, "2026-11-09T07:30:00.125Z");
    }

    private void claim(UUID orderId, UUID eventId, Instant holdExpiresAt, String recordedAt) {
        byte[] body = ("{\"claimId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + orderId + "\",\"method\":\"MTN\","
                + "\"referenceNormalised\":\"MP2411ABC\",\"submittedAt\":\"2026-11-09T07:30:00Z\",\"holdExpiresAt\":\"" + holdExpiresAt + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        orderEvents.accept(Relayed.orderEvent(eventId, "order.payment_submitted", orderId, 3, body)
                .setHeader(EventHeaders.RECORDED_AT, recordedAt).build());
    }

    private void orderEnds(UUID orderId, String type) {
        byte[] body = ("{\"orderId\":\"" + orderId + "\",\"keycloakId\":\"kc-alice\",\"previousStatus\":\"PAYMENT_SUBMITTED\",\"newStatus\":\"X\","
                + "\"total\":180.00,\"currency\":\"ZMW\"}").getBytes(StandardCharsets.UTF_8);
        orderEvents.accept(Relayed.orderEvent(UUID.randomUUID(), type, orderId, 4, body).build());
    }

    private Map<String, Object> alert(UUID orderId) {
        return jdbc.queryForMap("SELECT * FROM staff_alert WHERE kind = 'PAYMENT_TO_CONFIRM' AND subject_id = ?", orderId.toString());
    }

    private List<Map<String, Object>> staffRows(String notice) {
        return jdbc.queryForList("SELECT * FROM notification_log WHERE recipient_kind = 'STAFF' AND alert_notice = ? ORDER BY staff_to, recorded_at", notice);
    }

    private static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    // ---- raised, told, reminded, ended ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a claim raises one alert and one email per money mailbox, due at once and worth sending until the hold ends")
    void aClaimRaisesOneAlertAndOneEmailPerMoneyAddress() {
        UUID orderId = UUID.randomUUID();
        claim(orderId);

        Map<String, Object> alert = alert(orderId);
        assertThat(alert).containsEntry("state", "OPEN").containsEntry("audience", "MONEY").containsEntry("order_id", orderId)
                .containsEntry("email_limited", false);
        assertThat(alert.get("summary")).isEqualTo("A customer says they have paid - order " + orderId.toString().substring(0, 8).toUpperCase());
        assertThat(at(alert.get("raised_at"))).isEqualTo(START);
        assertThat(at(alert.get("tell_before"))).isEqualTo(HOLD_ENDS);
        assertThat(at(alert.get("remind_at"))).as("three hours before the hold ends").isEqualTo(HOLD_ENDS.minus(Duration.ofHours(3)));
        assertThat(at(alert.get("closes_at"))).isEqualTo(HOLD_ENDS.plus(Duration.ofMinutes(15)));
        assertThat(alert.get("emailed_at")).as("nobody has been told yet: an email written down is not an email taken").isNull();

        List<Map<String, Object>> rows = staffRows("FIRST");
        assertThat(rows).extracting(row -> row.get("staff_to")).containsExactlyElementsOf(MONEY);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsEntry("event_type", "alert.payment_to_confirm").containsEntry("status", "RECORDED")
                    .containsEntry("audience", "MONEY").containsEntry("alert_tell_no", 1).containsEntry("order_id", orderId);
            assertThat(row.get("keycloak_id")).as("a staff row has no owner: it is in nobody's bell").isNull();
            assertThat(row.get("alert_id")).isEqualTo(alert.get("id"));
            assertThat(at(row.get("next_attempt_at"))).isEqualTo(START);
            assertThat(at(row.get("send_before"))).isEqualTo(HOLD_ENDS);
            assertThat(row.get("subject")).isEqualTo(alert.get("summary"));
            assertThat(row.get("body")).isEqualTo(alert.get("detail"));
        });
    }

    @Test
    @DisplayName("the words name the method and when the hold ends, and never an amount or the customer's reference")
    void theWordsNameNoAmountAndNoReference() {
        UUID orderId = UUID.randomUUID();
        orderEnds(orderId, "order.created");
        jdbc.update("DELETE FROM notification_log");
        claim(orderId);

        Map<String, Object> alert = alert(orderId);
        String all = alert.get("summary") + " " + alert.get("detail") + " " + alert.get("remind_subject") + " " + alert.get("remind_body");
        assertThat(all).contains("MTN mobile money").contains("19:31 on Monday 9 November")
                .doesNotContainPattern("K\\d").doesNotContain("MP2411ABC");
    }

    @Test
    @DisplayName("the same order's claim again raises nothing more, even after its alert has ended")
    void aRepeatedClaimRaisesNothingMore() {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        claim(orderId);
        orderEnds(orderId, "order.paid");
        claim(orderId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert", Long.class)).isEqualTo(1);
        assertThat(staffRows("FIRST")).hasSize(2);
    }

    @Test
    @DisplayName("a claim whose hold has already passed raises nothing; a claim read long after the fact is listed and emails nobody at once")
    void oldClaims() {
        UUID over = UUID.randomUUID();
        claim(over, UUID.randomUUID(), START.minusSeconds(1), "2026-11-09T07:30:00.125Z");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert", Long.class)).isZero();

        UUID readLate = UUID.randomUUID();
        claim(readLate, UUID.randomUUID(), HOLD_ENDS, "2026-11-09T06:59:00Z");
        assertThat(alert(readLate)).containsEntry("state", "OPEN");
        assertThat(alert(readLate).get("remind_at")).as("the reminder still goes").isNotNull();
        assertThat(staffRows("FIRST")).isEmpty();
    }

    @Test
    @DisplayName("a claim about another order than its envelope names is refused, and one in an unreadable shape too; a claim is never read as an order snapshot")
    void aClaimAboutAnotherOrderThanItsEnvelopeIsRefused() {
        UUID orderId = UUID.randomUUID();
        byte[] aboutAnother = ("{\"claimId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"method\":\"MTN\","
                + "\"holdExpiresAt\":\"" + HOLD_ENDS + "\"}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> orderEvents.accept(Relayed.orderEvent(UUID.randomUUID(), "order.payment_submitted", orderId, 3, aboutAnother).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderEvents.accept(Relayed.orderEvent(UUID.randomUUID(), "order.payment_submitted", orderId, 3,
                ("{\"orderId\":\"" + orderId + "\",\"method\":\"MTN\"}").getBytes(StandardCharsets.UTF_8)).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holdExpiresAt");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_ref", Long.class)).as("a claim is not an order snapshot").isZero();
    }

    @Test
    @DisplayName("'told' means a mail server took it: the alert is stamped when the first email is accepted, not when it is written")
    void emailedMeansAcceptedNotWritten() {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        transport.thenAnswer(SendResult.recipientTryLater());

        dispatcher().run();

        assertThat(transport.handedOver()).extracting(Email::to).containsExactlyElementsOf(MONEY);
        assertThat(transport.handedOver()).allSatisfy(email -> {
            assertThat(email.subject()).startsWith("A customer says they have paid - order ");
            assertThat(email.body()).doesNotContain("/orders/");
        });
        assertThat(at(alert(orderId).get("emailed_at"))).as("one mailbox took it").isEqualTo(START);
        assertThat(staffRows("FIRST")).extracting(row -> row.get("status")).containsExactly("RECORDED", "SENT");
    }

    @Test
    @DisplayName("staff are reminded once, three hours before the hold ends; two dispatchers reminding together write one set of emails")
    void remindsOnceBeforeTheHoldEnds() throws Exception {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        dispatcher().run();
        clock.set(HOLD_ENDS.minus(Duration.ofHours(3)));

        CyclicBarrier together = new CyclicBarrier(2);
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> {
            await(together);
            alerts.clock(clock.instant());
        });
        await(together);
        alerts.clock(clock.instant());
        other.get(20, TimeUnit.SECONDS);
        dispatcher().run();
        dispatcher().run();

        List<Map<String, Object>> reminders = staffRows("REMINDER");
        assertThat(reminders).extracting(row -> row.get("staff_to")).containsExactlyElementsOf(MONEY);
        assertThat(reminders).allSatisfy(row -> {
            assertThat(row).containsEntry("status", "SENT").containsEntry("alert_tell_no", 1);
            assertThat((String) row.get("subject")).startsWith("Still waiting - order ");
        });
        assertThat(alert(orderId).get("remind_at")).isNull();
        assertThat(alert(orderId)).containsEntry("reminders_sent", 1);
    }

    @Test
    @DisplayName("after a mail outage staff do not get the first notice and its reminder together: only the newer one goes")
    void firstIsNotSentOnceTheReminderIsWritten() {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        clock.set(HOLD_ENDS.minus(Duration.ofHours(3)));

        dispatcher().run();

        assertThat(staffRows("FIRST")).allSatisfy(row -> assertThat(row).containsEntry("status", "NOT_SENT").containsEntry("status_reason", "SUPERSEDED"));
        assertThat(staffRows("REMINDER")).allSatisfy(row -> assertThat(row).containsEntry("status", "SENT"));
        assertThat(transport.handedOver()).hasSize(2);
    }

    @Test
    @DisplayName("the order being paid, expiring or being cancelled ends the alert, stops its reminder, and an email not yet sent is not sent")
    void paidExpiredOrCancelledClosesItAndStopsTheReminder() {
        for (String[] ending : new String[][] {{"order.paid", "PAID"}, {"order.expired", "EXPIRED"}, {"order.cancelled", "CANCELLED"}}) {
            UUID orderId = UUID.randomUUID();
            claim(orderId);
            orderEnds(orderId, ending[0]);

            assertThat(alert(orderId)).as(ending[0]).containsEntry("state", "RESOLVED").containsEntry("resolved_how", ending[1]);
            assertThat(alert(orderId).get("remind_at")).isNull();
        }

        dispatcher().run();
        clock.set(HOLD_ENDS.minus(Duration.ofHours(3)));
        dispatcher().run();

        assertThat(staffRows("FIRST")).hasSize(6).allSatisfy(row ->
                assertThat(row).containsEntry("status", "NOT_SENT").containsEntry("status_reason", "ALERT_RESOLVED"));
        assertThat(staffRows("REMINDER")).isEmpty();
        assertThat(transport.handedOver()).filteredOn(email -> email.subject().startsWith("A customer says")).isEmpty();
    }

    @Test
    @DisplayName("if nothing ends it, the alert closes by itself a quarter of an hour after the hold has ended")
    void closesByItselfAfterTheHoldHasPassed() {
        UUID orderId = UUID.randomUUID();
        claim(orderId);

        clock.set(HOLD_ENDS.plus(Duration.ofMinutes(14)));
        alerts.clock(clock.instant());
        assertThat(alert(orderId)).containsEntry("state", "OPEN");

        clock.set(HOLD_ENDS.plus(Duration.ofMinutes(15)));
        alerts.clock(clock.instant());
        assertThat(alert(orderId)).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "BY_CLOCK");
    }

    // ---- nobody to tell, and telling again -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("with no mailbox configured the alert stands and one row says nobody was told; once a mailbox is given, it is told")
    void anAlertRaisedWithNobodyConfiguredIsToldWhenAnAddressIsGiven() {
        UUID orderId = UUID.randomUUID();
        StaffAlerts nobodyConfigured = new StaffAlerts(jdbc, transactions, catcher(List.of()), settings);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                nobodyConfigured.claimReceived(orderId, "MTN", HOLD_ENDS, START, START));

        assertThat(alert(orderId)).containsEntry("state", "OPEN");
        assertThat(staffRows("FIRST")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "NOT_SENT").containsEntry("status_reason", "NO_RECIPIENT_CONFIGURED");
            assertThat(row.get("staff_to")).isNull();
        });
        dispatcher(catcher(List.of()), nobodyConfigured).run();
        assertThat(transport.handedOver()).isEmpty();

        // The service is restarted with a mailbox. An hour after the last word on this alert, it is written again and goes.
        clock.advance(Duration.ofMinutes(61));
        dispatcher().run();

        assertThat(staffRows("AGAIN")).extracting(row -> row.get("staff_to")).containsExactlyElementsOf(MONEY);
        assertThat(staffRows("AGAIN")).allSatisfy(row -> assertThat(row).containsEntry("status", "SENT").containsEntry("alert_tell_no", 2));
        assertThat(alert(orderId).get("emailed_at")).isNotNull();

        clock.advance(Duration.ofMinutes(61));
        dispatcher().run();
        assertThat(staffRows("AGAIN")).as("once told, it is not told again").hasSize(2);
    }

    @Test
    @DisplayName("an alert whose emails gave up is told again when mail works; nothing is told again while email is off or paused")
    void anAlertWhoseEmailGaveUpIsToldAgainWhenMailWorks() {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        transport.thenAnswer(SendResult.refused(SendResult.ADDRESS_REFUSED), SendResult.refused(SendResult.ADDRESS_REFUSED));
        dispatcher().run();
        assertThat(staffRows("FIRST")).allSatisfy(row -> assertThat(row).containsEntry("status", "FAILED"));

        clock.advance(Duration.ofMinutes(61));
        EmailSetup pausedSetup = new EmailSetup(Mode.CATCHER, null, MONEY, List.of(), null, true);
        dispatcher(pausedSetup, new StaffAlerts(jdbc, transactions, pausedSetup, settings)).run();
        assertThat(staffRows("AGAIN")).as("paused: nothing is written again").isEmpty();

        dispatcher().run();
        assertThat(staffRows("AGAIN")).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("status", "SENT"));
    }

    // ---- many claims in a day ----------------------------------------------------------------------------------------------------------------

    private StaffAlerts limitedTo(int perDay) {
        return new StaffAlerts(jdbc, transactions, catcher(MONEY), TestSettings.shippedWith(Map.of(
                "oriah.notification.alerts.payment-to-confirm.max-alerts-emailed-per-day", Integer.toString(perDay))));
    }

    private void claimDirectly(StaffAlerts staffAlerts, UUID orderId) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> staffAlerts.claimReceived(orderId, "MTN", HOLD_ENDS, START, START));
    }

    @Test
    @DisplayName("beyond the day's limit a claim is still raised and listed, it is never emailed, and ONE email says so")
    void reachingTheClaimLimitTellsStaffOnce(CapturedOutput output) {
        StaffAlerts limited = limitedTo(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        for (UUID orderId : List.of(first, second, third, fourth)) {
            claimDirectly(limited, orderId);
        }

        assertThat(alert(first)).containsEntry("email_limited", false);
        assertThat(alert(second)).as("the limit counts alerts, not mailboxes: two alerts went to both mailboxes").containsEntry("email_limited", false);
        assertThat(alert(third)).containsEntry("email_limited", true).containsEntry("state", "OPEN");
        assertThat(alert(third).get("remind_at")).as("a limited alert is not reminded of by email either").isNull();
        assertThat(jdbc.queryForList("SELECT status, status_reason, staff_to FROM notification_log WHERE alert_id = ?", alert(third).get("id")))
                .singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("status", "NOT_SENT").containsEntry("status_reason", "DAILY_LIMIT");
                    assertThat(row.get("staff_to")).isNull();
                });

        Map<String, Object> said = jdbc.queryForMap("SELECT * FROM staff_alert WHERE kind = 'CLAIM_EMAIL_LIMIT'");
        assertThat(said).containsEntry("state", "OPEN").containsEntry("subject_id", "2026-11-09");
        assertThat((String) said.get("detail")).startsWith("More than 2 customers said in the last day that they have paid.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert WHERE kind = 'CLAIM_EMAIL_LIMIT'", Long.class)).as("said once").isEqualTo(1);
        assertThat(output.getAll()).contains("each new one is listed but no longer emailed");

        dispatcher(catcher(MONEY), limited).run();
        clock.advance(Duration.ofMinutes(61));
        dispatcher(catcher(MONEY), limited).run();
        assertThat(transport.handedOver()).extracting(Email::subject)
                .filteredOn(subject -> subject.startsWith("Many customers say")).as("the one email that says so, to each mailbox").hasSize(2);
        assertThat(transport.handedOver()).as("two claims and the notice, each to two mailboxes; the limited claims are never emailed").hasSize(6);
    }

    @Test
    @DisplayName("two claims racing for the last place of the day take one between them")
    void twoClaimsRacingForTheLastPlaceTakeOne() throws Exception {
        StaffAlerts limited = limitedTo(1);
        UUID one = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        CyclicBarrier together = new CyclicBarrier(2);
        CompletableFuture<Void> racing = CompletableFuture.runAsync(() -> {
            await(together);
            claimDirectly(limited, other);
        });
        await(together);
        claimDirectly(limited, one);
        racing.get(20, TimeUnit.SECONDS);

        assertThat(List.of(alert(one).get("email_limited"), alert(other).get("email_limited"))).containsExactlyInAnyOrder(true, false);
    }

    // ---- email itself in trouble ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the mail server failing for half an hour is itself an alert: listed and logged once, never emailed, and cleared by the next message it takes")
    void emailsNotGoingOut(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        transport.thenAnswer(SendResult.serverTryLater(SendResult.CONNECT));
        dispatcher().run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert WHERE kind = 'EMAIL_DOWN'", Long.class)).as("not yet").isZero();

        // Half an hour later it is still failing, with the one queued message waiting out its back-off.
        clock.advance(Duration.ofMinutes(30));
        transport.thenAnswer(SendResult.serverTryLater(SendResult.CONNECT));
        dispatcher().run();
        clock.advance(Duration.ofMinutes(1));
        dispatcher().run();

        Map<String, Object> down = jdbc.queryForMap("SELECT * FROM staff_alert WHERE kind = 'EMAIL_DOWN'");
        assertThat(down).containsEntry("state", "OPEN").containsEntry("subject_id", "email").containsEntry("summary", "Emails are not going out");
        assertThat((String) down.get("detail")).contains("since 09:31 on Monday 9 November");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE alert_id = ?", Long.class, down.get("id")))
                .as("it cannot be emailed: email is what is down").isZero();
        assertThat(output.getAll()).containsOnlyOnce("Emails are not going out");

        clock.set(health.state().quietUntil());
        dispatcher().run();
        assertThat(jdbc.queryForMap("SELECT state, resolved_how FROM staff_alert WHERE kind = 'EMAIL_DOWN'"))
                .containsEntry("state", "RESOLVED").containsEntry("resolved_how", "RECOVERED");
    }

    @Test
    @DisplayName("'emails are not going out' clears by itself when the server answers again, even with nothing waiting to be sent")
    void emailDownClearsWhenTheServerAnswersWithNothingQueued() {
        health.serverFailed(START, Duration.ofMinutes(1), Duration.ofMinutes(15));
        clock.advance(Duration.ofMinutes(31));
        transport.probeAnswers(SendResult.serverTryLater(SendResult.CONNECT));
        dispatcher().run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert WHERE kind = 'EMAIL_DOWN' AND state = 'OPEN'", Long.class)).isEqualTo(1);
        assertThat(transport.probes()).as("with nothing queued, the server is asked whether it is there").isEqualTo(1);
        assertThat(health.state().failures()).isEqualTo(2);

        clock.set(health.state().quietUntil());
        transport.probeAnswers(SendResult.accepted());
        dispatcher().run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert WHERE kind = 'EMAIL_DOWN' AND state = 'OPEN'", Long.class)).isZero();
        assertThat(health.state().failingSince()).isNull();
        assertThat(transport.handedOver()).as("a probe sends nothing").isEmpty();
    }

    @Test
    @DisplayName("a refused alert mailbox is listed and logged without its address; the other mailbox still gets the alert, once")
    void aRefusedAlertMailboxIsListedAndLogged(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        transport.thenAnswer(SendResult.refused(SendResult.ADDRESS_REFUSED));

        dispatcher().run();
        dispatcher().run();

        assertThat(staffRows("FIRST")).extracting(row -> row.get("status")).containsExactly("FAILED", "SENT");
        assertThat(transport.handedOver()).hasSize(2);
        assertThat(jdbc.queryForMap("SELECT state, summary FROM staff_alert WHERE kind = 'EMAIL_DOWN' AND subject_id = 'alert-mailbox'"))
                .containsEntry("state", "OPEN").containsEntry("summary", "An alert email address is being refused");
        assertThat(output.getAll()).contains("An alert mailbox was refused").doesNotContain("alerts-money@oriah.local")
                .doesNotContain("alerts-owner@oriah.local");
    }

    @Test
    @DisplayName("all email paused: the alert clock keeps running (reminders are written, alerts close), nothing is sent, and what is written is 'sending off'")
    void pausedStillClosesAndRemindsAlerts() {
        UUID orderId = UUID.randomUUID();
        claim(orderId);
        EmailSetup pausedSetup = new EmailSetup(Mode.CATCHER, null, MONEY, List.of(), null, true);
        MessageDispatcher paused = dispatcher(pausedSetup, new StaffAlerts(jdbc, transactions, pausedSetup, settings));
        paused.run();
        clock.set(HOLD_ENDS.minus(Duration.ofHours(3)));
        paused.run();
        paused.run();

        assertThat(transport.handedOver()).isEmpty();
        assertThat(staffRows("FIRST")).allSatisfy(row -> assertThat(row).containsEntry("status_reason", "SENDING_OFF"));
        assertThat(staffRows("REMINDER")).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("status_reason", "SENDING_OFF"));

        clock.set(HOLD_ENDS.plus(Duration.ofMinutes(15)));
        paused.run();
        assertThat(alert(orderId)).containsEntry("resolved_how", "BY_CLOCK");
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
