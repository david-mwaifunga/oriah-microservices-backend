package com.oriah.checkout.exception;

/**
 * The bag holds lines priced in more than one currency.
 *
 * <p>Reachable: catalog enforces one currency per PRODUCT, not across
 * products, so two variants of different products can legitimately disagree.
 * Summing them is meaningless and picking one charges the wrong number in the
 * wrong denomination, so the bag is refused.
 *
 * <p>Distinct from {@link BagNotPurchasableException} because the remedy is
 * different: every line here is individually fine, and the shopper has to
 * split the order rather than remove something unavailable.
 */
public class MixedCurrencyException extends IllegalStateException {

    public MixedCurrencyException(String first, String second) {
        super("Your bag mixes " + first + " and " + second
                + ", so it cannot be checked out as one order.");
    }
}
