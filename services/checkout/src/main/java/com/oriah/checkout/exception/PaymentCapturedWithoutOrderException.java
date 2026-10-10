package com.oriah.checkout.exception;

import java.util.UUID;

/**
 * Money was captured and the order could not be advanced to {@code PAID}.
 *
 * <p>The worst state this system can reach, and the reason this exception
 * carries ids rather than just a message: a human has to reconcile it, and they
 * cannot do that without knowing which payment intent to look at.
 *
 * <p><strong>500</strong>, because it is exactly what a 500 is for — the server
 * is in a state it should not be in. It used to be {@code 200 OK} with
 * {@code "message": "order/pay failed (payment captured)"}, so the shopper saw
 * "Order placed", their card was charged, and no order existed. Nothing
 * anywhere raised an alarm.
 *
 * <p>A 500 is not a fix; it is a floor. The real answer is a compensating
 * action — refund the intent, or retry the transition from an outbox — and that
 * is a saga concern beyond this tier. What this does achieve is that the
 * failure is now <em>loud</em>: a 5xx the dashboards count, a problem body with
 * the intent id, and an ERROR log line.
 */
public class PaymentCapturedWithoutOrderException extends RuntimeException {

    private final UUID orderId;
    private final UUID paymentIntentId;

    public PaymentCapturedWithoutOrderException(UUID orderId, UUID paymentIntentId,
                                                Throwable cause) {
        super(("The payment succeeded but the order could not be completed. "
                + "Your payment is captured and support can reconcile it; "
                + "do not retry."), cause);
        this.orderId = orderId;
        this.paymentIntentId = paymentIntentId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID paymentIntentId() {
        return paymentIntentId;
    }
}
