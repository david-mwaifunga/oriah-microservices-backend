package com.oriah.payment.confirmation;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How money reached Oriah. CASH has no reference worth keeping unique; every other method carries one that the provider (or the bank) shows, and
 * the same reference can never confirm money twice.
 *
 * <p>{@code CARD} is recorded only from a payment a provider collected and verified (Phase 13): a person never keys a card payment, claims one or
 * pays a refund "by card", so {@link #parse} does not know it.
 */
public enum PaymentMethod {

    CASH, MTN, AIRTEL, ZAMTEL, BANK_TRANSFER, CARD;

    /**
     * The method a PERSON named: staff confirming money, a shopper claiming a payment, the owner paying a refund.
     *
     * @throws IllegalArgumentException if the text names no method a person may use (the message lists the ones there are)
     */
    public static PaymentMethod parse(String text) {
        if (text != null) {
            String wanted = text.strip().toUpperCase(Locale.ROOT);
            for (PaymentMethod method : values()) {
                if (method.name().equals(wanted) && method.keyedByAPerson()) {
                    return method;
                }
            }
            if (CARD.name().equals(wanted)) {
                throw new IllegalArgumentException("CARD is recorded only from a card payment the provider collected: it is never keyed by a person");
            }
        }
        throw new IllegalArgumentException("method must be one of "
                + Arrays.stream(values()).filter(PaymentMethod::keyedByAPerson).map(Enum::name).collect(Collectors.joining(", ")) + ", was '" + text + "'");
    }

    /** Whether this method must come with a reference (everything but cash). */
    public boolean requiresReference() {
        return this != CASH;
    }

    /** Whether a person may name this method. */
    public boolean keyedByAPerson() {
        return this != CARD;
    }
}
