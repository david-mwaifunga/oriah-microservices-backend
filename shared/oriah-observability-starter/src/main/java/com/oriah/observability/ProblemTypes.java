package com.oriah.observability;

import java.net.URI;

/**
 * Problem types that more than one service emits or recognises.
 *
 * <p>Most problem types stay local to the service that raises them. These are
 * shared because one service answers with them and another acts on them by
 * {@code type} -- checkout passes shipping's "outside the delivery area" on to
 * the shopper as a 422 rather than a generic 500 -- so the URI must not drift
 * between the two copies that would otherwise exist.
 */
public final class ProblemTypes {

    /** 422: a destination Oriah does not deliver to. Oriah sells within Zambia only. */
    public static final URI OUTSIDE_DELIVERY_AREA =
            URI.create("https://oriah.dev/problems/outside-delivery-area");

    /**
     * 409: what the shopper agreed to is no longer what would be charged --
     * the basket, a price, or a delivery price changed since the quote. Order
     * raises it before saving anything; checkout passes it on, so the
     * storefront re-quotes and asks the shopper to look again.
     */
    public static final URI PRICING_CHANGED =
            URI.create("https://oriah.dev/problems/pricing-changed");

    private ProblemTypes() {
    }
}