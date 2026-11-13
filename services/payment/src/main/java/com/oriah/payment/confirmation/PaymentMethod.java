package com.oriah.payment.confirmation;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How money reached Oriah, as staff confirm it. CASH has no reference worth keeping unique; every other method carries one that the provider
 * (or the bank) shows, and the same reference can never confirm money twice.
 */
public enum PaymentMethod {
    CASH, MTN, AIRTEL, ZAMTEL, BANK_TRANSFER;

    /**
     * @throws IllegalArgumentException if the text names no method (the message lists the ones there are)
     */
    public static PaymentMethod parse(String text) {
        if (text != null) {
            String wanted = text.strip().toUpperCase(Locale.ROOT);
            for (PaymentMethod method : values()) {
                if (method.name().equals(wanted)) {
                    return method;
                }
            }
        }
        throw new IllegalArgumentException("method must be one of " + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "))
                + ", was '" + text + "'");
    }

    /** Whether this method must come with a reference (everything but cash). */
    public boolean requiresReference() {
        return this != CASH;
    }
}
