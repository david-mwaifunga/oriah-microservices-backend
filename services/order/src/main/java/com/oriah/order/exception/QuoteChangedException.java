package com.oriah.order.exception;

/**
 * 409 pricing-changed: what the shopper agreed to is no longer what would be
 * charged -- the delivery option is no longer offered, or (item 4.4) the
 * basket or a price moved since the quote. Raised before anything is saved,
 * so nothing is ordered or charged; the storefront re-quotes.
 */
public class QuoteChangedException extends RuntimeException {

    public static final String DETAIL = "Your basket or its price changed -- please review it before paying.";

    public QuoteChangedException() {
        super(DETAIL);
    }
}