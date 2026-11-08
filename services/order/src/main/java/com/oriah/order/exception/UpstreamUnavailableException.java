package com.oriah.order.exception;

/**
 * 503 upstream-unavailable: a service order needs to price the order did not
 * answer. Nothing has been placed or charged, so a retry is safe.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String upstream) {
        super(upstream + " did not answer");
    }
}