package com.oriah.checkout.exception;

/**
 * A promotion code was sent, and codes are not accepted (MVP_PLAN Phase 0,
 * item 4.2). 422 {@code promotion-codes-not-accepted}.
 *
 * <p>Today's code-based, whole-order promotion is retired by Phase 7's scoped
 * promotions -- one product, one category or the whole shop, applied by
 * themselves and worked out per piece. Wiring the old one into the new quote
 * would be thrown away. So a code is refused OUT LOUD rather than ignored: a
 * shopper whose code vanished without a word would believe the discount was
 * applied.
 */
public class PromotionCodesNotAcceptedException extends RuntimeException {

    public PromotionCodesNotAcceptedException() {
        super("Promotion codes are not accepted at the moment. Remove the code to continue.");
    }
}
