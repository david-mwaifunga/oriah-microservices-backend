package com.oriah.notification.alerts;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.oriah.notification.alerts.StaffAlerts.Kind;
import com.oriah.notification.alerts.StaffAlerts.NewAlert;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.model.MessageKind;
import com.oriah.notification.model.NotificationLog;
import com.oriah.notification.model.StatusReason;
import com.oriah.notification.orders.OrderRefs;
import com.oriah.notification.repository.NotificationLogRepository;
import com.oriah.notification.text.MessageTexts;
import com.oriah.notification.text.MessageTexts.Text;

/**
 * What notification knows about one refund that is owed (Phase 12), one row per debt ({@code refund_watch}; the debt's id is the
 * correction's), and what follows from it. The facts come from two topics in any order: that money is owed (order's correction), that it
 * was paid out, that a payout was cancelled, that the debt was closed because the money never arrived (payment). Each locks the debt's row
 * and then everything is decided again from what the row says, so the order of arrival never leaves anything wrong:
 *
 * <ul>
 *   <li><b>the alert "a refund is waiting to be paid"</b> is open while the debt is known, is not closed, and no payout of it stands (paid
 *       and not cancelled). It is listed at once and emailed only after it has stood for days, then weekly; it never closes by the clock;</li>
 *   <li><b>"your refund has been paid"</b> is written for the customer once BOTH the payout and the correction are known, whichever is
 *       heard second, and waits behind the correction's own message.</li>
 * </ul>
 *
 * It runs in the consumer's transaction, with the Inbox's "applied" marker. Rows are taken in one order everywhere: the confirmation's row
 * (order's consumer only), then the debt's row, then the alert.
 */
@Component
public class RefundWatch {

    private static final Logger log = LoggerFactory.getLogger(RefundWatch.class);

    private record Watch(UUID debtId, UUID orderId, BigDecimal owed, String currency, Instant owedSince, UUID messageId, Instant closedAt) {

        boolean owedKnown() {
            return owedSince != null && owed.signum() > 0;
        }
    }

    private record Payout(UUID payoutId, boolean paid, boolean voided, BigDecimal gross, BigDecimal customerFee, String currency, UUID eventId,
                          Instant eventRecordedAt, boolean told) {

        boolean stands() {
            return paid && !voided;
        }
    }

    private final JdbcTemplate jdbc;
    private final StaffAlerts alerts;
    private final OrderRefs orderRefs;
    private final NotificationLogRepository messages;
    private final NotificationSettings settings;

    public RefundWatch(JdbcTemplate jdbc, StaffAlerts alerts, OrderRefs orderRefs, NotificationLogRepository messages, NotificationSettings settings) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.orderRefs = orderRefs;
        this.messages = messages;
        this.settings = settings;
    }

    /**
     * Order's correction: money is owed to the customer since the correction happened.
     *
     * @param messageId the message written for the customer about the correction; null when none was (a phone order)
     */
    public void owed(UUID debtId, UUID orderId, BigDecimal owed, String currency, Instant owedSince, UUID messageId, Instant now) {
        lock(debtId, now);
        jdbc.update("""
                UPDATE refund_watch
                   SET order_id = COALESCE(order_id, ?), owed_gross = COALESCE(owed_gross, ?), currency = COALESCE(currency, ?),
                       owed_since = COALESCE(owed_since, ?), message_id = COALESCE(message_id, ?), updated_at = ?
                 WHERE debt_id = ?""", orderId, owed, currency, Timestamp.from(owedSince), messageId, Timestamp.from(now), debtId);
        decide(debtId, now);
    }

    /** {@code payment.paid_out} for a refund: the money has gone back. */
    public void paidOut(UUID payoutId, UUID debtId, BigDecimal gross, BigDecimal customerFee, BigDecimal net, String currency, UUID eventId,
                        Instant eventRecordedAt, Instant now) {
        lock(debtId, now);
        jdbc.update("""
                INSERT INTO refund_watch_payout (payout_id, debt_id, paid_seen, gross, customer_fee, net, currency, event_id, event_recorded_at)
                VALUES (?, ?, TRUE, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (payout_id) DO UPDATE
                   SET paid_seen = TRUE, gross = COALESCE(refund_watch_payout.gross, EXCLUDED.gross),
                       customer_fee = COALESCE(refund_watch_payout.customer_fee, EXCLUDED.customer_fee), net = COALESCE(refund_watch_payout.net, EXCLUDED.net),
                       currency = COALESCE(refund_watch_payout.currency, EXCLUDED.currency), event_id = COALESCE(refund_watch_payout.event_id, EXCLUDED.event_id),
                       event_recorded_at = COALESCE(refund_watch_payout.event_recorded_at, EXCLUDED.event_recorded_at)""",
                payoutId, debtId, gross, customerFee, net, currency, eventId, Timestamp.from(eventRecordedAt));
        decide(debtId, now);
    }

    /** {@code payment.payout_voided}: the payout was cancelled in payment, so the debt stands again. */
    public void payoutVoided(UUID payoutId, UUID debtId, Instant now) {
        lock(debtId, now);
        jdbc.update("""
                INSERT INTO refund_watch_payout (payout_id, debt_id, voided_seen) VALUES (?, ?, TRUE)
                ON CONFLICT (payout_id) DO UPDATE SET voided_seen = TRUE""", payoutId, debtId);
        // "Your refund has been paid" may already be written down and still waiting (behind the correction's message, for an address, for the
        // mail server): it is no longer true, so it is withdrawn before it can go. One that was already sent stays as it is.
        int withdrawn = jdbc.update("""
                UPDATE notification_log SET status = 'NOT_SENT', status_reason = 'SUPERSEDED', status_at = ?, next_attempt_at = NULL
                 WHERE status = 'RECORDED' AND event_type = 'payment.paid_out'
                   AND event_id = (SELECT event_id FROM refund_watch_payout WHERE payout_id = ?)""", Timestamp.from(now), payoutId);
        if (withdrawn > 0) {
            log.info("A payout was cancelled before its 'refund paid' message went out: the message is withdrawn");
        }
        decide(debtId, now);
    }

    /** {@code payment.refund_closed}: the money this debt would have returned never arrived; nothing is owed any more. */
    public void closed(UUID debtId, Instant closedAt, Instant now) {
        lock(debtId, now);
        jdbc.update("UPDATE refund_watch SET closed_at = COALESCE(closed_at, ?), updated_at = ? WHERE debt_id = ?", Timestamp.from(closedAt), Timestamp.from(now), debtId);
        decide(debtId, now);
    }

    // ---- deciding --------------------------------------------------------------------------------------------------------------------------

    private void decide(UUID debtId, Instant now) {
        Watch watch = jdbc.queryForObject("SELECT * FROM refund_watch WHERE debt_id = ?",
                (row, n) -> new Watch(row.getObject("debt_id", UUID.class), row.getObject("order_id", UUID.class), row.getBigDecimal("owed_gross"),
                        row.getString("currency"), instant(row.getTimestamp("owed_since")), row.getObject("message_id", UUID.class),
                        instant(row.getTimestamp("closed_at"))), debtId);
        List<Payout> payouts = jdbc.query("SELECT * FROM refund_watch_payout WHERE debt_id = ? ORDER BY payout_id",
                (row, n) -> new Payout(row.getObject("payout_id", UUID.class), row.getBoolean("paid_seen"), row.getBoolean("voided_seen"),
                        row.getBigDecimal("gross"), row.getBigDecimal("customer_fee"), row.getString("currency"), row.getObject("event_id", UUID.class),
                        instant(row.getTimestamp("event_recorded_at")), row.getBoolean("told")), debtId);

        if (watch.owedSince() != null) {
            payouts.stream().filter(payout -> payout.paid() && !payout.told()).forEach(payout -> tellTheCustomer(watch, payout, now));
        }

        String subject = debtId.toString();
        boolean paid = payouts.stream().anyMatch(Payout::stands);
        if (watch.owedKnown() && watch.closedAt() == null && !paid) {
            raise(watch, subject, now);
        } else if (watch.closedAt() != null) {
            alerts.resolve(Kind.REFUND_TO_PAY, subject, "MONEY_NEVER_CAME", now);
        } else if (paid) {
            alerts.resolve(Kind.REFUND_TO_PAY, subject, "PAID_OUT", now);
        }
    }

    /** Listed at once; emailed only by its reminder, after it has stood for {@code alerts.refund-to-pay.remind-after}. */
    private void raise(Watch watch, String subject, Instant now) {
        String receipt = orderRefs.find(watch.orderId()).map(OrderRefs.OrderRef::receiptNumber).orElse(null);
        Text words = MessageTexts.refundToPay(watch.orderId(), receipt, watch.owed(), watch.currency(), watch.owedSince(), settings.zone());
        alerts.raise(new NewAlert(Kind.REFUND_TO_PAY, subject, watch.orderId(), words, null, now.plus(settings.alerts().refundToPay().remindAfter()), words, null,
                false), now).ifPresent(id -> log.info("A refund for order {} is owed: listed for the staff who look after money", watch.orderId()));
    }

    /**
     * "Your refund has been paid", once: for a customer with an account, and only for a payout that still stands. Whatever is decided, the
     * payout is marked as dealt with, so a repeat writes nothing.
     */
    private void tellTheCustomer(Watch watch, Payout payout, Instant now) {
        jdbc.update("UPDATE refund_watch_payout SET told = TRUE WHERE payout_id = ?", payout.payoutId());
        if (payout.voided()) {
            return;
        }
        OrderRefs.OrderRef order = orderRefs.find(watch.orderId()).orElse(null);
        if (order == null || order.phoneOrder() || order.keycloakId() == null) {
            // A caller has no account and no email: staff were on the phone with them to arrange the payout.
            return;
        }
        Text text = MessageTexts.refundPaid(watch.orderId(), order.receiptNumber(), payout.gross(), payout.customerFee(), payout.currency());
        Instant sendBefore = payout.eventRecordedAt().plus(settings.send().window());
        StatusReason notSentBecause = !settings.email().emailed().contains(MessageKind.REFUND_PAID) ? StatusReason.BELL_ONLY
                : !now.isBefore(sendBefore) ? StatusReason.TOO_LATE : null;
        messages.save(NotificationLog.forShopperAfter(watch.orderId(), order.keycloakId(), "payment.paid_out", text.subject(), text.body(), payout.eventId(),
                payout.eventRecordedAt(), now, notSentBecause, sendBefore, watch.messageId()));
        log.info("payment.paid_out for order {} written down as a message: {}", watch.orderId(),
                notSentBecause == null ? "waiting to be emailed" : "not emailed (" + notSentBecause + ")");
    }

    /** Makes sure the debt's row exists and locks it for this transaction. */
    private void lock(UUID debtId, Instant now) {
        jdbc.update("INSERT INTO refund_watch (debt_id, updated_at) VALUES (?, ?) ON CONFLICT (debt_id) DO NOTHING", debtId, Timestamp.from(now));
        jdbc.query("SELECT 1 FROM refund_watch WHERE debt_id = ? FOR UPDATE", row -> { }, debtId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
