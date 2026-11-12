package com.oriah.procurement.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catalog could not be asked, so nothing was saved: 503 with {@code Retry-After}.
 *
 * <p>Never answered with a guess. "Is this a real variant?" has no safe default: yes lets
 * a mistyped id be ordered and paid for, no refuses a good order. The caller is told to
 * try again.
 */
public class CatalogUnavailableException extends ResponseStatusException {

    static final String RETRY_AFTER_SECONDS = "5";

    public CatalogUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "The catalogue could not be asked whether these variants exist, so nothing was saved. "
                        + "Try again in a moment.", cause);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return headers;
    }
}
