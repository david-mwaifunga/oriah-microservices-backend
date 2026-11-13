package com.oriah.inventory.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A request about boxes set aside for a product that was understood and cannot be done, with a stable name a screen can switch on: the
 * problem's {@code type} is {@code https://oriah.dev/problems/<code>}. Extra members (how many boxes are free) ride along as properties
 * of the problem body.
 */
public class CommitmentProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public CommitmentProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public CommitmentProblem with(String name, Object value) {
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
