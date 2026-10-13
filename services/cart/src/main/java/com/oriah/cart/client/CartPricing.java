package com.oriah.cart.client;

import java.util.Map;
import java.util.UUID;

/**
 * Catalog's answer about a whole bag, plus whether it answered at all.
 *
 * <h2>Why "unavailable" is a separate flag and not an empty map</h2>
 *
 * Two situations look identical in a map and must not be treated alike:
 *
 * <ul>
 *   <li><b>Catalog answered and said nothing about a variant.</b> That is a
 *       refusal. The line cannot be sold, shows no price, and contributes
 *       nothing to the subtotal -- because reading a missing answer as a price
 *       is how a missing answer becomes a free item.</li>
 *   <li><b>Catalog could not be reached.</b> Nothing was established about any
 *       line, so the bag falls back to its stored snapshots and nothing in it
 *       may be bought. A shopper still gets to see what they put in it.</li>
 * </ul>
 *
 * Collapsing the two -- by treating an empty map as an outage -- would make a
 * catalog bug that returns no results look like a network problem and hand the
 * shopper stored prices as if they were live. Collapsing them the other way
 * would empty a bag during an outage.
 *
 * @param available whether catalog answered at all
 * @param entries   per-variant answers; always empty when {@code available} is
 *                  false
 */
public record CartPricing(boolean available, Map<UUID, CatalogPricingClient.Entry> entries) {

    public static CartPricing unavailable() {
        return new CartPricing(false, Map.of());
    }

    public static CartPricing of(Map<UUID, CatalogPricingClient.Entry> entries) {
        return new CartPricing(true, Map.copyOf(entries));
    }

    /** An empty bag needs no call, and catalog rejects an empty id list. */
    public static CartPricing nothingToPrice() {
        return new CartPricing(true, Map.of());
    }

    /** @return catalog's answer for this variant, or null if it gave none. */
    public CatalogPricingClient.Entry entryFor(UUID variantId) {
        return entries.get(variantId);
    }
}
