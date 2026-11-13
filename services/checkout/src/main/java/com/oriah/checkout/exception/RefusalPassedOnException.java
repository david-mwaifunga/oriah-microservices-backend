package com.oriah.checkout.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A refusal by order or shipping that the SHOPPER can act on, passed on under its own name (Phase 7): inventory's {@code insufficient-stock} naming every
 * short line, order's {@code hold-limit-reached}, shipping's {@code town-not-offered}. The status is the downstream's own (409); the type is
 * {@code https://oriah.dev/problems/<code>} and any extra members ({@code shortages}, ...) ride along.
 *
 * <p>Only answers of a kind checkout KNOWS are passed on this way; any other refusal is checkout's own bug and stays a generic 500
 * ({@link DownstreamRejectedException}).
 */
public class RefusalPassedOnException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extras;

    public RefusalPassedOnException(HttpStatus status, String code, String detail, Map<String, Object> extras) {
        super(detail != null ? detail : "That cannot be done right now: " + code);
        this.status = status;
        this.code = code;
        this.extras = new LinkedHashMap<>(extras);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> extras() {
        return extras;
    }
}
