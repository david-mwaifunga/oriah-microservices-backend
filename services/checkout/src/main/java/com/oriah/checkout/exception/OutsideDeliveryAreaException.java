package com.oriah.checkout.exception;

/**
 * The shopper's address is somewhere Oriah does not deliver to -- shipping
 * answered {@code outside-delivery-area}. 422, passed on in checkout's own
 * words rather than as a generic 500 (MVP_PLAN Phase 0, item 1): the request
 * was well-formed, and the shopper can act on the answer by choosing a
 * Zambian address. Nothing is ordered or charged.
 *
 * <p>The detail is checkout's, never shipping's body echoed, so no internal
 * path or service name reaches the storefront.
 */
public class OutsideDeliveryAreaException extends RuntimeException {

    public static final String MESSAGE = "We deliver within Zambia only.";

    public OutsideDeliveryAreaException() {
        super(MESSAGE);
    }
}