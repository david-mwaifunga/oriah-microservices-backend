package com.oriah.notification.alerts;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.oriah.notification.alerts.StaffAlerts.Kind;
import com.oriah.notification.alerts.StaffAlerts.NewAlert;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.orders.OrderRefs;
import com.oriah.notification.text.MessageTexts;
import com.oriah.notification.text.MessageTexts.MoneyWords;
import com.oriah.notification.text.MessageTexts.Text;

/**
 * What staff are told about a payment through ZynlePay (Phase 13).
 *
 * <p>Only one thing is worth an alert: a payment ZynlePay did not settle. It has not answered, or it answered something the system does not act on
 * by itself (another amount, "paid" after "failed", money already keyed by hand), or its record was cancelled. The customer may have paid, and
 * without this nobody would find out. The alert is told at once, again a day later if still open, and ends when the payment is settled: ZynlePay
 * answers, or the owner decides.
 *
 * <p>A payment that failed or was verified in the ordinary way tells staff nothing: the customer sees it on the page they are paying on, and a
 * verified one is announced as paid by the order.
 */
@Component
public class ProviderPaymentWatch {

    /** How the alert ends: ZynlePay answered, or the owner decided. */
    static final String SETTLED = "PAYMENT_SETTLED";
    private static final Duration REMIND_AFTER = Duration.ofDays(1);
    /** As long as payment itself goes on asking ZynlePay about an unanswered payment. */
    private static final Duration OPEN_FOR = Duration.ofDays(14);

    private final StaffAlerts alerts;
    private final OrderRefs orderRefs;
    private final NotificationSettings settings;

    public ProviderPaymentWatch(StaffAlerts alerts, OrderRefs orderRefs, NotificationSettings settings) {
        this.alerts = alerts;
        this.orderRefs = orderRefs;
        this.settings = settings;
    }

    /**
     * @param status           UNRESOLVED, FAILED or VERIFIED
     * @param unresolvedReason why the system could not settle it, when UNRESOLVED
     * @param recordedAt       when payment recorded the fact: one read long afterwards (history) raises nothing
     */
    public void updated(UUID providerPaymentId, UUID orderId, String method, String status, String unresolvedReason, BigDecimal amount, String currency,
                        Instant recordedAt, Instant now) {
        String subject = providerPaymentId.toString();
        if (!"UNRESOLVED".equals(status)) {
            alerts.resolve(Kind.PROVIDER_PAYMENT_UNRESOLVED, subject, SETTLED, now);
            return;
        }
        if (recordedAt == null || Duration.between(recordedAt, now).compareTo(settings.alerts().maxFactAge()) > 0) {
            return;
        }
        String receipt = orderRefs.find(orderId).map(OrderRefs.OrderRef::receiptNumber).orElse(null);
        MoneyWords money = new MoneyWords(orderId, receipt, amount, currency, method);
        Text words = MessageTexts.providerPaymentUnresolved(money, unresolvedReason);
        alerts.raise(new NewAlert(Kind.PROVIDER_PAYMENT_UNRESOLVED, subject, orderId, words, null, now.plus(REMIND_AFTER),
                        MessageTexts.providerPaymentUnresolvedReminder(money), now.plus(OPEN_FOR), false), now)
                .ifPresent(id -> alerts.tell(id, Kind.PROVIDER_PAYMENT_UNRESOLVED, "FIRST", 1, words, now, null, now));
    }
}
