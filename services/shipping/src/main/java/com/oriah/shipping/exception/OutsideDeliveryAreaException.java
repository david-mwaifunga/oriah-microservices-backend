package com.oriah.shipping.exception;

/**
 * The destination is somewhere Oriah does not deliver to. Oriah sells within
 * Zambia only (MVP_PLAN Phase 0, item 1), so any other country is refused --
 * never quoted, and never charged zero. 422, problem type
 * {@code outside-delivery-area}: the request is well-formed, and the shopper
 * can act on the answer by choosing a Zambian address.
 */
public class OutsideDeliveryAreaException extends RuntimeException {

    /** The words the shopper sees; checkout repeats them rather than inventing its own. */
    public static final String MESSAGE = "We deliver within Zambia only.";

    public OutsideDeliveryAreaException() {
        super(MESSAGE);
    }
}