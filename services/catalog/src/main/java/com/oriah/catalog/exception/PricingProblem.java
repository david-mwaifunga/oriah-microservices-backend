package com.oriah.catalog.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A pricing request (or one about a product's default box, which prices read) that was understood and cannot be done, with a stable
 * name a screen can switch on: the problem's {@code type} is {@code https://oriah.dev/problems/<code>}. Extra members (the figures
 * behind a below-cost warning, the products that hold a box) ride along as properties of the problem body.
 */
public class PricingProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public PricingProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public PricingProblem with(String name, Object value) {
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
