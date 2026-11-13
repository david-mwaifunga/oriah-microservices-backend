package com.oriah.catalog.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Inventory could not be asked what the stock cost, so no price was compared with it and nothing was saved: 503 with
 * {@code Retry-After}.
 *
 * <p>Never answered with a guess. "No cost known" would let a price below cost through unseen; refusing a good price is the
 * lesser harm, and the person is told to try again in a moment.
 */
public class CostUnavailableException extends ResponseStatusException {

    static final String RETRY_AFTER_SECONDS = "5";

    public CostUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "Stock could not be asked what it cost, so the price was not compared with it and nothing was saved. "
                        + "Try again in a moment.", cause);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return headers;
    }
}
