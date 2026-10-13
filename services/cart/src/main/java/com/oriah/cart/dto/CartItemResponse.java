package com.oriah.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.cart.client.CatalogPricingClient;
import com.oriah.cart.model.CartItem;

/**
 * One line of the bag, as the API publishes it.
 *
 * <h2>The money here is catalog's current answer, not the stored snapshot</h2>
 *
 * The {@code cart_items.unit_price} column is a snapshot from when the shopper
 * added the line. Reporting it would show a total nobody is going to be
 * charged, because checkout reprices from catalog at confirm. So a PRICED line
 * reports catalog's live price and its composed label, and the snapshot is used
 * only when catalog could not be reached at all.
 *
 * @param pricingStatus PRICED, UNPUBLISHED or UNKNOWN.
 *                      <p>Deliberately NOT called {@code availability}: catalog's
 *                      own {@code ProductResponse} already publishes a field of
 *                      that name whose values are IN_STOCK / LOW_STOCK /
 *                      OUT_OF_STOCK / UNKNOWN. The two would share a name AND
 *                      share the value UNKNOWN with a different meaning, in a
 *                      storefront that mirrors both by hand.
 * @param unitPrice     null when the line cannot be sold. A withdrawn variant
 *                      has no price to show -- catalog omits it precisely so a
 *                      stale one cannot be read off the response by accident --
 *                      and showing the old snapshot beside "no longer
 *                      available" invites the shopper to expect that price back.
 * @param lineTotal     always consistent with {@code unitPrice}: zero for a line
 *                      that cannot be sold. {@code CartResponse.subtotal} is the
 *                      sum of these, so the bag always adds up to the lines it
 *                      is showing.
 */
public record CartItemResponse(
        UUID id,
        UUID variantId,
        String title,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String pricingStatus,
        String currency
) {

    public static final String PRICED = "PRICED";
    public static final String UNPUBLISHED = "UNPUBLISHED";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * Maps a stored line against catalog's answer for it.
     *
     * @param entry catalog's answer, or null when catalog ANSWERED and said
     *              nothing about this variant -- which is treated exactly like
     *              a refusal, because reading a missing answer as a price is
     *              how a missing answer becomes a free item. Not to be confused
     *              with catalog being unreachable; that is
     *              {@link #degraded(CartItem)}.
     */
    public static CartItemResponse from(CartItem i, CatalogPricingClient.Entry entry) {
        if (entry != null && entry.isPriced()) {
            BigDecimal unitPrice = entry.unitPrice();
            return new CartItemResponse(
                    i.id(), i.variantId(),
                    // Catalog's label too, not the stored one: if a product was
                    // renamed, the bag should say what the shop now calls it.
                    entry.variantTitle() == null ? i.title() : entry.variantTitle(),
                    i.quantity(),
                    unitPrice,
                    unitPrice.multiply(BigDecimal.valueOf(i.quantity())),
                    PRICED,
                    entry.currency());
        }

        // Catalog answered, and the answer is no. UNPUBLISHED and UNKNOWN are
        // both reported as themselves -- the storefront renders both as "no
        // longer available", but an operator reading a response wants to know
        // which, and neither reveals anything a shopper cannot already learn
        // by trying to add the variant.
        String status = entry == null ? UNKNOWN
                : UNPUBLISHED.equals(entry.status()) ? UNPUBLISHED
                : UNKNOWN;
        return new CartItemResponse(
                i.id(), i.variantId(), i.title(), i.quantity(),
                null, BigDecimal.ZERO, status, null);
    }

    /**
     * Catalog could not be reached, so this line falls back to its stored
     * snapshot.
     *
     * <p>Reported as UNKNOWN with the stored price, which is the one case where
     * a price appears beside a non-PRICED status. The alternative -- showing an
     * empty bag, or zeroes -- is worse for a shopper who just wants to see what
     * they put in it. {@code CartResponse.checkoutEnabled} is false throughout,
     * so nothing can be bought at a price this service is not sure of.
     */
    public static CartItemResponse degraded(CartItem i) {
        return new CartItemResponse(
                i.id(), i.variantId(), i.title(), i.quantity(),
                i.unitPrice(),
                i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())),
                UNKNOWN,
                i.currency());
    }

    /** Whether this line may be sold at the price it is showing. */
    public boolean isPurchasable() {
        return PRICED.equals(pricingStatus);
    }
}
