package com.oriah.finance.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A refusal the caller can act on, answered as problem+json under {@code https://oriah.dev/problems/<code>} with any extra properties alongside. The code is
 * the slug the screens branch on (for example {@code customs-charge-not-a-tax-entry}); the detail is the plain sentence a person reads.
 */
public class FinanceProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public FinanceProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public FinanceProblem with(String name, Object value) {
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
