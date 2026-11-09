package com.oriah.checkout.exception;

/**
 * The payment service could not be reached, or answered unusably, while
 * confirming an intent.
 *
 * <p><strong>502 Bad Gateway</strong>: an upstream dependency failed to give a
 * usable answer. Separate from {@link PaymentDeclinedException} (402, final)
 * and from {@link DownstreamUnavailableException} (503, nothing attempted yet),
 * because the three need different client behaviour and this one is the
 * ambiguous case — the charge may or may not have gone through.
 *
 * <p>Only when the request to charge reached payment at least once. When it
 * never left checkout -- confirm's circuit breaker open, the rate limiter
 * refusing, the connection never opened, the load balancer holding no address
 * for payment -- nothing can have been charged, and that is the 503 instead
 * ({@code NothingSentException}).
 *
 * <p>The order already exists -- it is placed before payment is asked for -- and
 * that ambiguity is why it is left in {@code CREATED} and the cart is left
 * intact: the state has to stay recoverable.
 *
 * <h2>Exactly how safe a retry is</h2>
 *
 * <p>An earlier version of this javadoc said a retry was safe "because payment
 * confirmation is idempotent on {@code Idempotency-Key}". Half of that was
 * false when written. payment-service does implement the header — it replays a
 * stored response rather than re-charging — but checkout forwarded only
 * {@code Authorization}, so the key the browser sent was dropped and the
 * guarantee was unreachable. Introducing a 502 that invites a retry while the
 * key went nowhere would have turned every retry into a second charge, so the
 * forwarding is now part of this change (finding #19) and pinned by
 * {@code theIdempotencyKeyReachesPaymentService}.
 *
 * <p>What that buys, precisely: with the same key resent, <strong>the card is
 * not charged twice</strong>. What it does not buy: the retry still places a
 * second order and creates a second intent before reaching the replayed
 * confirm, so the first order can be left stranded in {@code CREATED}. One
 * charge, one usable order, one abandoned row — strictly better than a double
 * charge, and not yet correct. Making the whole saga idempotent needs a
 * checkout-level record keyed on the same header; recorded as the residual gap
 * rather than implied to be solved.
 */
public class PaymentConfirmationFailedException extends RuntimeException {

    /**
     * The message is what the shopper reads (the 502's problem detail), so it
     * says what is true at this point: the order IS recorded -- placed before
     * payment, CREATED and unpaid -- and the charge may or may not have gone
     * through. It used to say "Your order has not been placed; check your
     * payment method before retrying": false on both counts, and an invitation
     * to place a second order.
     *
     * <p>It then said "check your orders before trying again", which cannot
     * work: nothing moves the order out of CREATED when a charge it never
     * heard about goes through (payment's reconciler settles only the intent),
     * so the order list shows "Pending" either way. A shopper who checked,
     * saw Pending and tried again after a reload -- with a new Idempotency-Key
     * -- could be charged twice. So the advice is not to pay again, and to
     * tell the shop if the money left their account.
     */
    public PaymentConfirmationFailedException(String detail, Throwable cause) {
        super("Your order is recorded, but its payment could not be confirmed (" + detail
                + "), so the charge may or may not have gone through. "
                + "Please do not pay again: the order shows as pending until the payment has been checked. "
                + "Contact us if you were charged.", cause);
    }
}
