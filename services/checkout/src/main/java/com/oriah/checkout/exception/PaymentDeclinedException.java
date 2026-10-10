package com.oriah.checkout.exception;

/**
 * The payment processor refused the card.
 *
 * <p><strong>402 Payment Required</strong>, which is the one status in this tier
 * that the shared advice does not already map — hence checkout's own
 * {@link GlobalExceptionHandler}. It is deliberately not added to the shared
 * starter: 402 is meaningful in exactly one service, and a shared mapping would
 * be a status eleven other services could never produce correctly.
 *
 * <p>Distinct from {@link PaymentConfirmationFailedException} on purpose. A
 * decline is <strong>final</strong> — the same card will be refused again, so
 * this must never be retried — while an unreachable processor is transient and
 * should be. Both used to be the same {@code 200 OK}, separated only by the
 * wording of a {@code message} string.
 *
 * <p>This is the defect that mattered most. A declined card produced a green
 * {@code toast.success("Order placed")} whose subtitle read
 * "payment declined: card declined", and because the frontend's
 * {@code if (res.orderId)} redirect never fired, the shopper was left on the
 * checkout page believing the purchase had succeeded.
 *
 * <p>The processor's reason IS included in the body: the shopper needs to know
 * whether to try another card, and it reveals nothing about the system.
 */
public class PaymentDeclinedException extends RuntimeException {

    private final String reason;

    public PaymentDeclinedException(String reason) {
        super("The payment was declined" + (reason == null ? "." : ": " + reason));
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
