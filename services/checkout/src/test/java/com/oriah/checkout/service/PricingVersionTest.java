package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.checkout.client.CatalogPricingClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token that says "these are the prices I was shown".
 *
 * <h2>What it is for</h2>
 *
 * Preview quotes a bag; the shopper reads the total and presses Place order.
 * Between those two moments an admin can reprice a variant or withdraw it. The
 * version is a digest of the prices preview used, echoed back by confirm, so
 * the server can tell whether the shopper is agreeing to the numbers it
 * actually showed them -- and refuse rather than silently charge different
 * ones.
 *
 * <h2>Sorted by variantId, because response order is not stable</h2>
 *
 * {@link #theSameBagInADifferentOrderHasTheSameVersion} is the one that stops
 * this being useless. Cart's {@code items} has no {@code @OrderBy} and its
 * repository fetches with an entity graph and no ORDER BY, so line order is
 * whatever Hibernate returns and can differ between two reads of an unchanged
 * bag. A digest over the response sequence would therefore mismatch for
 * reasons that have nothing to do with price, and every shopper would meet a
 * 409 on a bag nobody had touched.
 *
 * <h2>Quantity is deliberately not an input</h2>
 *
 * A shopper changing their own quantity has not been shown a different price,
 * and the total is recomputed at confirm regardless. Including quantity would
 * turn the ordinary act of nudging a stepper into a conflict.
 *
 * <h2>Status is an input, which is less obvious</h2>
 *
 * A variant going UNPUBLISHED between preview and confirm changes nothing
 * about the prices that remain, so a price-only digest would still match --
 * and the shopper would be refused by the availability check with no
 * explanation of what changed. Including status makes "something in your bag
 * changed" the honest answer.
 */
class PricingVersionTest {

    private static final UUID RING = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUFF = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static CatalogPricingClient.Entry entry(UUID variantId, String price, String currency,
                                                    String status) {
        return new CatalogPricingClient.Entry(variantId, status, UUID.randomUUID(),
                "Product", "Product - variant",
                price == null ? null : new BigDecimal(price), currency);
    }

    private static CatalogPricingClient.Entry priced(UUID variantId, String price) {
        return entry(variantId, price, "ZMW", "PRICED");
    }

    @Test
    @DisplayName("the same bag in a different order has the same version")
    void theSameBagInADifferentOrderHasTheSameVersion() {
        String forwards = PricingVersion.of(List.of(priced(RING, "4500.00"), priced(CUFF, "250.00")));
        String backwards = PricingVersion.of(List.of(priced(CUFF, "250.00"), priced(RING, "4500.00")));

        assertThat(forwards)
                .as("cart's line order is not stable, so a sequence-sensitive digest "
                        + "would 409 on a bag nobody touched")
                .isEqualTo(backwards);
    }

    @Test
    @DisplayName("a changed price changes the version")
    void aChangedPriceChangesTheVersion() {
        assertThat(PricingVersion.of(List.of(priced(RING, "4500.00"))))
                .isNotEqualTo(PricingVersion.of(List.of(priced(RING, "4600.00"))));
    }

    @Test
    @DisplayName("a price that differs only in scale does NOT change the version")
    void trailingZeroesDoNotChangeTheVersion() {
        // 4500.00 and 4500.000 are the same amount of money. BigDecimal's
        // toString keeps the scale, so digesting it raw would make a harmless
        // change in how catalog formats a column -- or a migration that
        // widened it -- look like a repricing to every shopper mid-checkout.
        assertThat(PricingVersion.of(List.of(priced(RING, "4500.00"))))
                .isEqualTo(PricingVersion.of(List.of(priced(RING, "4500.000"))));
    }

    @Test
    @DisplayName("a changed currency changes the version")
    void aChangedCurrencyChangesTheVersion() {
        assertThat(PricingVersion.of(List.of(entry(RING, "4500.00", "ZMW", "PRICED"))))
                .as("the same number in another unit is a different price")
                .isNotEqualTo(PricingVersion.of(List.of(entry(RING, "4500.00", "USD", "PRICED"))));
    }

    @Test
    @DisplayName("a changed status changes the version")
    void aChangedStatusChangesTheVersion() {
        assertThat(PricingVersion.of(List.of(entry(RING, "4500.00", "ZMW", "PRICED"))))
                .isNotEqualTo(PricingVersion.of(List.of(entry(RING, null, null, "UNPUBLISHED"))));
    }

    @Test
    @DisplayName("adding or removing a line changes the version")
    void changingTheLinesChangesTheVersion() {
        String one = PricingVersion.of(List.of(priced(RING, "4500.00")));
        String two = PricingVersion.of(List.of(priced(RING, "4500.00"), priced(CUFF, "250.00")));

        assertThat(one).isNotEqualTo(two);
    }

    @Test
    @DisplayName("two different variants at the same price are not interchangeable")
    void theVariantIdIsPartOfTheDigest() {
        // Without the id in the digest, swapping one 250.00 ring for another
        // would keep the version valid -- and the staleness check would pass
        // for a bag whose contents had changed entirely.
        assertThat(PricingVersion.of(List.of(priced(RING, "250.00"))))
                .isNotEqualTo(PricingVersion.of(List.of(priced(CUFF, "250.00"))));
    }

    @Test
    @DisplayName("the version is stable across calls and opaque to the client")
    void theVersionIsStableAndOpaque() {
        List<CatalogPricingClient.Entry> bag = List.of(priced(RING, "4500.00"));

        assertThat(PricingVersion.of(bag)).isEqualTo(PricingVersion.of(bag));
        assertThat(PricingVersion.of(bag))
                .as("a hex digest: nothing a client can usefully take apart or forge "
                        + "a plausible value for")
                .matches("[0-9a-f]{64}");
    }
}
