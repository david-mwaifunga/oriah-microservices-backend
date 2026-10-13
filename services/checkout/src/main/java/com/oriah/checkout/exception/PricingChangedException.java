package com.oriah.checkout.exception;

/**
 * The prices moved between the preview the shopper read and the confirm they
 * sent.
 *
 * <h2>Why this is not CartEmptyException with a different message</h2>
 *
 * Both are 409s, and before this class existed the storefront mapped EVERY 409
 * to "Your bag is empty" with retry disabled. A shopper whose ring was
 * repriced by 50 ngwee would be told their bag was empty while looking at it,
 * and offered no way forward. The distinct {@code type} URI is what lets the
 * client say something true -- and mark it retryable, because a fresh preview
 * fixes it.
 *
 * @param changedLines the variant ids whose price, currency or availability
 *                     moved. Carried so the storefront can mark the specific
 *                     lines rather than the whole bag; it is the shopper's own
 *                     bag, so naming its contents discloses nothing.
 */
public class PricingChangedException extends IllegalStateException {

    private final java.util.List<java.util.UUID> changedLines;

    public PricingChangedException(java.util.List<java.util.UUID> changedLines) {
        super("Prices in your bag changed while you were checking out. "
                + "Review the updated total and try again.");
        this.changedLines = java.util.List.copyOf(changedLines);
    }

    public java.util.List<java.util.UUID> changedLines() {
        return changedLines;
    }
}
