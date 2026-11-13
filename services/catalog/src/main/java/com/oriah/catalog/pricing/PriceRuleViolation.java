package com.oriah.catalog.pricing;

/** A price or its date broke a pricing rule. The code is the stable name a caller can switch on (it becomes the problem's type). */
public class PriceRuleViolation extends IllegalArgumentException {

    private final String code;

    public PriceRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
