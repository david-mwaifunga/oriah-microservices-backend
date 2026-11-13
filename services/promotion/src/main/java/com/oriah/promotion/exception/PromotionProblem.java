package com.oriah.promotion.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A request about promotions that was understood and cannot be done, with a stable name a screen can switch on: the problem's {@code type} is
 * {@code https://oriah.dev/problems/<code>}.
 */
public class PromotionProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public PromotionProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public PromotionProblem with(String name, Object value) {
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
