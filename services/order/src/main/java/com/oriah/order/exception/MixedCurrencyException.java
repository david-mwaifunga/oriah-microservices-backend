package com.oriah.order.exception;

/**
 * The lines do not all price in one currency.
 *
 * <p>Its own type rather than folded into "not purchasable" because the remedy
 * differs: an unsellable line is removed, whereas a mixed-currency order is
 * split into two. checkout keeps the same distinction for the same reason.
 *
 * <p>The message does not name the two currencies. They are a property of the
 * catalogue rather than of the caller's input, and an order is refused either
 * way.
 */
public class MixedCurrencyException extends IllegalStateException {

    public MixedCurrencyException() {
        super("An order cannot mix currencies. Please order these items separately.");
    }
}
