package com.oriah.order.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A refusal in the sale that the caller can act on, answered as problem+json under {@code https://oriah.dev/problems/<code>} with any extra
 * properties alongside (for example {@code shortages}). Inventory's own refusals of a hold are passed through under their own names, so the
 * storefront sees {@code insufficient-stock} naming every short line, not an order-flavoured paraphrase.
 */
public class SalesProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public SalesProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public SalesProblem with(String name, Object value) {
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
