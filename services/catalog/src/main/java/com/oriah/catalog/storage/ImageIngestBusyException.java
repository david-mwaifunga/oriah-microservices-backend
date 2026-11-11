package com.oriah.catalog.storage;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catalog has no memory to spare for another photograph right now.
 *
 * <p>A 503 with {@code Retry-After}, not a 500: nothing is wrong with the picture or the
 * service, the admin only has to try again in a moment. It extends
 * {@link ResponseStatusException}, which the shared error advice already renders in the
 * standard problem shape with the exception's own headers, so it needs no handler of its own.
 */
public class ImageIngestBusyException extends ResponseStatusException {

    /** Seconds the caller is told to wait. A photograph is processed in a second or two. */
    static final String RETRY_AFTER_SECONDS = "5";

    public ImageIngestBusyException() {
        this("Photograph processing is busy; try the upload again in a few seconds.");
    }

    public ImageIngestBusyException(String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return headers;
    }
}
