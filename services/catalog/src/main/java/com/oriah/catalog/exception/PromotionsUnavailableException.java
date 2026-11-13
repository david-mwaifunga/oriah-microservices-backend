package com.oriah.catalog.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Promotion could not be asked what is in force, so no price was worked out: 503 with {@code Retry-After}.
 *
 * <p>Never answered with a guess. "No promotion" would charge the list price during a promotion, and the customer would be quoted it;
 * refusing to price for a moment is the lesser harm.
 */
public class PromotionsUnavailableException extends ResponseStatusException {

    static final String RETRY_AFTER_SECONDS = "5";

    public PromotionsUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "Promotions could not be checked, so no price was worked out. Try again in a moment.", cause);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return headers;
    }
}
