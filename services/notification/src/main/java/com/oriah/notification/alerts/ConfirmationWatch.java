package com.oriah.notification.alerts;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.oriah.notification.alerts.StaffAlerts.Kind;
import com.oriah.notification.alerts.StaffAlerts.NewAlert;
import com.oriah.notification.alerts.StaffAlerts.Stored;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.orders.OrderRefs;
import com.oriah.notification.text.MessageTexts;
import com.oriah.notification.text.MessageTexts.MoneyWords;
import com.oriah.notification.text.MessageTexts.Text;

/**
 * What notification knows about each staff-confirmed payment ({@code confirmation_watch}), and the alerts that follow from it.
 *
 * <p>The facts come from two topics in any order: payment says it recorded the money and announces it again and again until order answers;
 * order says what became of it. Each method here is one such fact. It runs in its consumer's transaction, LOCKS the confirmation's row first,
 * notes the fact, and only then decides, so the two consumers never decide on a stale picture of each other.
 *
 * <ul>
 *   <li><b>Stuck:</b> a second (or later) announcement with no answer known. Staff are told once (a minute later, in case the answer is only
 *       seconds behind), again after some hours, then daily; "sorted" when an answer comes, if they may have been told; a last note when the
 *       alert closes after two weeks with no answer.</li>
 *   <li><b>A cancelled record that paid a sale anyway:</b> both "voided" and "paid" are known for one confirmation, in either order.</li>
 *   <li><b>The claim alert:</b> once money is recorded for an order whose customer said they paid, "a customer says they have paid" steps
 *       aside (staff must not be reminded to confirm or reject money that is already recorded). If that money then cannot pay the order while
 *       the hold still runs, the claim is open again.</li>
 * </ul>
 *
 * <p>A fact recorded by its producer longer ago than {@code alerts.max-fact-age} still updates the row and still ends an alert, but raises
 * nothing and emails nobody: history read late (after an outage, or by a database that starts empty) is not news.
 */
@Component
public class ConfirmationWatch {

    private record Watch(UUID confirmationId, UUID orderId, String method, BigDecimal gross, String currency, Instant paidAt,
                         Instant unsettledAt, String unsettledReason, Instant voidedAt) {

        boolean answered() {
            return paidAt != null || unsettledAt != null || voidedAt != null;
        }
    }

    private final JdbcTemplate jdbc;
    private final StaffAlerts alerts;
    private final OrderRefs orderRefs;
    private final NotificationSettings settings;

    public ConfirmationWatch(JdbcTemplate jdbc, StaffAlerts alerts, OrderRefs orderRefs, NotificationSettings settings) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.orderRefs = orderRefs;
        this.settings = settings;
    }

    // ---- the facts -------------------------------------------------------------------------------------------------------------------------

    /**
     * {@code payment.confirmed}: staff recorded money, or payment is announcing it again.
     *
     * @param attempt which announcement this is: 1 when the money is first recorded
     * @param at      when payment recorded this announcement
     */
    public void confirmed(UUID confirmationId, UUID orderId, String method, BigDecimal gross, String currency, int attempt, Instant at, Instant now) {
        lock(confirmationId, orderId, at);
        jdbc.update("""
                UPDATE confirmation_watch
                   SET method = COALESCE(method, ?), gross = COALESCE(gross, ?), currency = COALESCE(currency, ?),
                       highest_attempt = GREATEST(highest_attempt, ?)
                 WHERE confirmation_id = ?""", method, gross == null ? null : gross, gross == null ? null : currency, attempt, confirmationId);
        Watch watch = read(confirmationId);
        if (watch.answered()) {
            return;
        }
        // Money is recorded and waiting to be applied: from here on "money recorded but the sale has not completed" owns it, and staff
        // must not be reminded to "confirm or reject" a payment that is already confirmed.
        alerts.resolve(Kind.PAYMENT_TO_CONFIRM, orderId.toString(), "MONEY_RECORDED", now);

        if (attempt >= 2 && fresh(at, now) && alerts.newest(Kind.SETTLEMENT_STUCK, confirmationId.toString()).isEmpty()) {
            MoneyWords money = money(watch);
            NotificationSettings.Settlement timing = settings.alerts().settlement();
            Text first = MessageTexts.settlementStuck(money);
            alerts.raise(new NewAlert(Kind.SETTLEMENT_STUCK, confirmationId.toString(), orderId, first, null, now.plus(timing.remindAfter()),
                            MessageTexts.settlementStillStuck(money), now.plus(timing.closeAfter()), false), now)
                    .ifPresent(id -> alerts.tell(id, Kind.SETTLEMENT_STUCK, "FIRST", 1, first, now.plus(settings.alerts().settleDelay()), null, now));
        }
    }

    /** {@code order.paid} naming this confirmation: the sale went through. Called for EVERY such event, a repeated one too. */
    public void paid(UUID confirmationId, UUID orderId, Instant at, Instant now) {
        lock(confirmationId, orderId, at);
        jdbc.update("UPDATE confirmation_watch SET paid_at = COALESCE(paid_at, ?) WHERE confirmation_id = ?", Timestamp.from(at), confirmationId);
        Watch watch = read(confirmationId);
        answer(watch, "PAID", fresh(at, now), now);
        if (watch.voidedAt() != null) {
            voidAfterSale(watch, fresh(at, now), now);
        }
    }

    /**
     * {@code order.payment_unsettled} or {@code payment.held}: the money could not pay the sale and is kept for the customer.
     *
     * @param reason a code (WRONG_AMOUNT, PIECE_SOLD ...), never typed text
     */
    public void unsettled(UUID confirmationId, UUID orderId, String reason, Instant at, Instant now) {
        lock(confirmationId, orderId, at);
        String code = reason == null || reason.isBlank() ? "UNKNOWN" : reason.length() > 30 ? reason.substring(0, 30) : reason;
        jdbc.update("""
                UPDATE confirmation_watch SET unsettled_at = COALESCE(unsettled_at, ?), unsettled_reason = COALESCE(unsettled_reason, ?)
                 WHERE confirmation_id = ?""", Timestamp.from(at), code, confirmationId);
        Watch watch = read(confirmationId);
        answer(watch, "UNSETTLED", fresh(at, now), now);
        reopenTheClaim(orderId, now);
    }

    /** {@code payment.voided}: staff cancelled the payment record. */
    public void voided(UUID confirmationId, UUID orderId, String method, BigDecimal gross, String currency, Instant at, Instant now) {
        lock(confirmationId, orderId, at);
        jdbc.update("""
                UPDATE confirmation_watch
                   SET method = COALESCE(method, ?), gross = COALESCE(gross, ?), currency = COALESCE(currency, ?), voided_at = COALESCE(voided_at, ?)
                 WHERE confirmation_id = ?""", method, gross == null ? null : gross, gross == null ? null : currency, Timestamp.from(at), confirmationId);
        Watch watch = read(confirmationId);
        answer(watch, "VOIDED", fresh(at, now), now);
        reopenTheClaim(orderId, now);
        if (watch.paidAt() != null) {
            voidAfterSale(watch, fresh(at, now), now);
        }
    }

    private boolean saleCorrected(UUID confirmationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT corrected_at IS NOT NULL FROM confirmation_watch WHERE confirmation_id = ?", Boolean.class,
                confirmationId));
    }

    /**
     * The sale this confirmation paid has been corrected (Phase 12: a paid order cancelled, or pieces returned): the order system has dealt
     * with it, so "a cancelled payment record paid a sale anyway" has its answer.
     *
     * @return whether the money record was cancelled (voided), as far as notification has heard: if so nobody knows yet whether the money
     *         arrived, and the customer is promised no amount
     */
    public boolean corrected(UUID confirmationId, UUID orderId, Instant at, Instant now) {
        lock(confirmationId, orderId, at);
        jdbc.update("UPDATE confirmation_watch SET corrected_at = COALESCE(corrected_at, ?) WHERE confirmation_id = ?", Timestamp.from(at), confirmationId);
        Watch watch = read(confirmationId);
        alerts.resolve(Kind.VOID_AFTER_SALE, confirmationId.toString(), "SALE_CORRECTED", now);
        return watch.voidedAt() != null;
    }

    /**
     * The last note of each "money recorded but the sale has not completed" alert that just closed by the clock: after two weeks of reminders
     * nobody answered, and an alert about money already taken must not end in silence. Called by the dispatcher right after the alert clock.
     *
     * <p>What is owed is read from what is stored (closed by the clock, no last note yet), so a stop between the close and the note loses
     * nothing: the next pass writes it.
     */
    public void sayLastNotes(Instant now) {
        for (StaffAlerts.Closed alert : alerts.closedByTheClockWithNoLastNote(Kind.SETTLEMENT_STUCK)) {
            Watch watch = read(UUID.fromString(alert.subjectId()));
            alerts.tellTheLastNote(alert.id(), Kind.SETTLEMENT_STUCK, MessageTexts.settlementNeverAnswered(money(watch)), now);
        }
    }

    // ---- what follows from a fact ------------------------------------------------------------------------------------------------------------

    /**
     * An answer for this confirmation has been noted. Its stuck alert, if there ever was one, ends (or, if it had closed by the clock, is
     * relabelled with the real answer so it leaves the "never answered" list). When staff may have been told of the alarm, they are told the
     * answer too, once: "may have been told" means some notice of it was at least tried, so a send that is in flight right now counts.
     */
    private void answer(Watch watch, String how, boolean fresh, Instant now) {
        Optional<Stored> stuck = alerts.newest(Kind.SETTLEMENT_STUCK, watch.confirmationId().toString());
        if (stuck.isEmpty()) {
            return;
        }
        Stored alert = stuck.get();
        if (alert.open()) {
            if (!alerts.resolve(Kind.SETTLEMENT_STUCK, watch.confirmationId().toString(), how, now)) {
                // It read open a moment ago and is not open now. Under the confirmation's lock only the clock can have ended it in between
                // (it closed by itself in that instant): the answer is still its real ending, or staff would read "we never heard".
                alerts.relabel(alert.id(), how);
            }
        } else if ("BY_CLOCK".equals(alert.resolvedHow())) {
            alerts.relabel(alert.id(), how);
        } else {
            // Already answered once: a second answer (a void after the money was held, say) changes nothing and says nothing more.
            return;
        }
        if (fresh && !alerts.said(alert.id(), "SORTED") && alerts.aNoticeMayHaveGoneOut(alert.id())) {
            Text sorted = MessageTexts.settlementSorted(money(watch), how, watch.unsettledReason());
            alerts.tell(alert.id(), Kind.SETTLEMENT_STUCK, "SORTED", 1, sorted, now.plus(settings.alerts().settleDelay()), null, now);
        }
    }

    /** Both "cancelled" and "paid" are known for one confirmation: goods may have left with no money behind them. Told once. */
    private void voidAfterSale(Watch watch, boolean fresh, Instant now) {
        String subject = watch.confirmationId().toString();
        // Not when the sale was already corrected (the correction can be heard before the void, and a repeated order.paid after both: they travel on
        // different topics): the order system has dealt with it, and an alert raised now would have nothing left to end it.
        if (!fresh || saleCorrected(watch.confirmationId()) || alerts.newest(Kind.VOID_AFTER_SALE, subject).isPresent()) {
            return;
        }
        Text words = MessageTexts.voidAfterSale(money(watch));
        alerts.raise(new NewAlert(Kind.VOID_AFTER_SALE, subject, watch.orderId(), words, null, null, null,
                        now.plus(settings.alerts().voidAfterSale().openFor()), false), now)
                .ifPresent(id -> alerts.tell(id, Kind.VOID_AFTER_SALE, "FIRST", 1, words, now, null, now));
    }

    /**
     * Money recorded for a claimed order ended held or cancelled. If no other money for the order is paid or still waiting for its answer,
     * the customer's claim is open again (while its hold still runs).
     */
    private void reopenTheClaim(UUID orderId, Instant now) {
        boolean otherMoneyStandsOrWaits = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM confirmation_watch
                                WHERE order_id = ? AND (paid_at IS NOT NULL OR (unsettled_at IS NULL AND voided_at IS NULL)))""",
                Boolean.class, orderId));
        if (!otherMoneyStandsOrWaits) {
            alerts.reopenClaim(orderId, now);
        }
    }

    // ---- the row ---------------------------------------------------------------------------------------------------------------------------

    /** Makes sure the confirmation's row exists and locks it for this transaction. {@code at} is the fact's own time, never ours. */
    private void lock(UUID confirmationId, UUID orderId, Instant at) {
        jdbc.update("""
                INSERT INTO confirmation_watch (confirmation_id, order_id, first_seen_at) VALUES (?, ?, ?)
                ON CONFLICT (confirmation_id) DO NOTHING""", confirmationId, orderId, Timestamp.from(at));
        jdbc.query("SELECT 1 FROM confirmation_watch WHERE confirmation_id = ? FOR UPDATE", row -> { }, confirmationId);
    }

    private Watch read(UUID confirmationId) {
        return jdbc.queryForObject("SELECT * FROM confirmation_watch WHERE confirmation_id = ?",
                (row, n) -> new Watch(row.getObject("confirmation_id", UUID.class), row.getObject("order_id", UUID.class), row.getString("method"),
                        row.getBigDecimal("gross"), row.getString("currency"), instant(row.getTimestamp("paid_at")),
                        instant(row.getTimestamp("unsettled_at")), row.getString("unsettled_reason"), instant(row.getTimestamp("voided_at"))),
                confirmationId);
    }

    private MoneyWords money(Watch watch) {
        String receipt = orderRefs.find(watch.orderId()).map(OrderRefs.OrderRef::receiptNumber).orElse(null);
        return new MoneyWords(watch.orderId(), receipt, watch.gross(), watch.currency(), watch.method());
    }

    private boolean fresh(Instant at, Instant now) {
        return Duration.between(at, now).compareTo(settings.alerts().maxFactAge()) <= 0;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
