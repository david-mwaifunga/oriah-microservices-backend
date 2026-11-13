package com.oriah.order.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How a shopper says they paid: the methods that leave a reference staff can look up. Cash is paid in person and is never claimed online
 * (staff confirm it at the counter), so it is not here. The names match payment's, which reads them from the claim event.
 */
public enum ClaimMethod {
    MTN, AIRTEL, ZAMTEL, BANK_TRANSFER;

    /** @throws IllegalArgumentException when the text names none of them (the message lists the ones there are) */
    public static ClaimMethod parse(String text) {
        if (text != null) {
            String wanted = text.strip().toUpperCase(Locale.ROOT);
            for (ClaimMethod method : values()) {
                if (method.name().equals(wanted)) {
                    return method;
                }
            }
        }
        throw new IllegalArgumentException("method must be one of " + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "))
                + " (cash is paid in person, not claimed online), was '" + text + "'");
    }
}
