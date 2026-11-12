package com.oriah.procurement.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The service has all the uploads it can hold in memory already and none finished while this one waited:
 * 503 with {@code Retry-After}, nothing stored. The caller is told to try again, not that something broke.
 */
public class ServerBusyException extends ResponseStatusException {

    static final String RETRY_AFTER_SECONDS = "5";

    public ServerBusyException() {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "The server is busy with other uploads and could not take this one yet. Nothing was stored: "
                        + "try again in a moment.");
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return headers;
    }
}
