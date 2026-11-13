package com.oriah.payment.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A request about staff-confirmed money that was understood and cannot be done, with a stable name a screen can switch on: the problem's
 * {@code type} is {@code https://oriah.dev/problems/<code>}, and what explains it (the confirmation already using a reference, the proof that was
 * missing) rides along as named properties.
 */
public class PaymentProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public PaymentProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public PaymentProblem with(String name, Object value) {
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
