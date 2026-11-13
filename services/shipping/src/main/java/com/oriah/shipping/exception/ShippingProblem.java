package com.oriah.shipping.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A request about delivery prices that was understood and cannot be done, with a stable name a screen can switch on: the problem's
 * {@code type} is {@code https://oriah.dev/problems/<code>}. Extra members (the current version of a stale copy, the payers a price offers)
 * ride along as properties of the problem body.
 */
public class ShippingProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ShippingProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public ShippingProblem with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
