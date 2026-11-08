package com.oriah.order.exception;

/**
 * 500: a service order depends on refused order's own request, or answered
 * with data order cannot use. Our bug or bad data -- never the shopper's
 * fault, so never a 400, and not retryable, so never a 503.
 */
public class UpstreamRejectedException extends RuntimeException {

    public UpstreamRejectedException(String message) {
        super(message);
    }
}