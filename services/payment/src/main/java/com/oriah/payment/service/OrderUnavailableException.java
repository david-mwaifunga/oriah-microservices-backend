package com.oriah.payment.service;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 503: order could not be asked whether the order is the caller's -- it did
 * not answer in time, could not be reached, or failed.
 *
 * <p>Nobody checked, so this is never the 404 "not your order": that would
 * tell an owner their own order is not theirs during an outage. Nothing has
 * been written or charged, so a retry is safe -- but not at once. This answer
 * comes only after payment has already waited on order, and it carries
 * {@code Retry-After}, so checkout does NOT repeat it (repeating it three times
 * would wait on order three times over): checkout answers the shopper with its
 * own 503, also carrying {@code Retry-After}, and the shopper tries again
 * later.
 *
 * <p>A {@link ResponseStatusException}, so the shared error advice renders it
 * as a 503 problem with this detail and keeps the {@code Retry-After} header.
 */
public class OrderUnavailableException extends ResponseStatusException {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    public OrderUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "We could not check this order just now. No payment has been set "
                + "up and nothing has been charged; please try again.", cause);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER.toSeconds()));
        return headers;
    }
}
