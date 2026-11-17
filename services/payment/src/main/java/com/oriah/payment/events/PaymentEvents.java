package com.oriah.payment.events;

/**
 * The topic every fact of payment is published on. The writers are {@link ConfirmationEvents} (money staff or a provider confirmed),
 * {@link RefundEvents} (money owed back and paid out) and the provider-payment writer (where a ZynlePay attempt stands).
 *
 * <p>The first card path (retired in Phase 13) published {@code payment.succeeded} and {@code payment.failed} here, for aggregate
 * {@code payment_intent}. Those rows stay in the log, as facts do; nothing publishes them any more, and every reader ignores them for good.
 */
public final class PaymentEvents {

    public static final String TOPIC = "oriah.payment";

    private PaymentEvents() {
    }
}
