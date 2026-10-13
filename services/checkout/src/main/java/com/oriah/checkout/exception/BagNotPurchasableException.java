package com.oriah.checkout.exception;

/**
 * The bag contains something catalog will not sell: withdrawn, unknown, or
 * priced at zero because nobody has priced it yet.
 *
 * <h2>Refuse the bag, do not quietly fix it</h2>
 *
 * Dropping the offending line would place an order the shopper never agreed
 * to. Charging around it would ship it free -- which is exactly what would
 * have happened once cart started excluding unsellable lines from its subtotal
 * while still listing them. So the whole bag is refused and the shopper
 * decides what to remove.
 *
 * <p>Its own {@code type} URI for the same reason as
 * {@link PricingChangedException}: the storefront used to render every 409 as
 * "Your bag is empty", which is both wrong and unactionable when the real
 * problem is one named line.
 */
public class BagNotPurchasableException extends IllegalStateException {

    private final java.util.List<java.util.UUID> unavailableLines;

    public BagNotPurchasableException(java.util.List<java.util.UUID> unavailableLines) {
        super("One or more items in your bag are no longer available. "
                + "Remove them and try again.");
        this.unavailableLines = java.util.List.copyOf(unavailableLines);
    }

    public java.util.List<java.util.UUID> unavailableLines() {
        return unavailableLines;
    }
}
