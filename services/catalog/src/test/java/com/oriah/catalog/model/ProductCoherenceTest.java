package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two states a product must not be able to reach. Both are things the
 * storefront read model would otherwise have to render, and neither has a
 * sensible rendering.
 *
 * <h2>Mixed currencies within one product</h2>
 *
 * The product list shows a "from" price per product, which means picking one
 * currency for one card. A product whose variants are priced in different
 * currencies has no correct card: "from K4,500" is wrong if the cheapest
 * variant is in dollars, and showing two currencies on one tile is not a
 * price. This also feeds the single-currency-per-cart rule, which can only be
 * enforced cheaply if a product cannot contribute two currencies by itself.
 *
 * <h2>An ACTIVE product with nothing purchasable</h2>
 *
 * Publishing already requires at least one active variant priced above zero.
 * The inverse was missing: nothing stopped an operator deactivating the last
 * such variant afterwards, leaving a product that is live on the storefront
 * and cannot be bought. The list query inner-joins active variants to compute
 * its price range, so such a product would silently vanish from the grid
 * while still answering on its own URL — present and unbuyable, which is the
 * worst of both.
 *
 * <p>Refused rather than silently unpublished: quietly taking a live product
 * off the storefront because of an edit to one variant is a bigger surprise
 * than an error saying which variant is load-bearing.
 */
class ProductCoherenceTest {

    private static Product ring() {
        return new Product("Aurelia Solitaire Ring", null, List.of());
    }

    private static ProductVariant variant(Product p, String sku, String price,
                                          String currency, Map<String, String> options) {
        return new ProductVariant(p, sku, new BigDecimal(price), currency, options);
    }

    @Test
    @DisplayName("every variant of a product must share one currency")
    void currencyIsUniformAcrossVariants() {
        var p = ring();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(variant(p, "AUR-M", "4500.00", "ZMW", Map.of("Ring Size", "M")));

        assertThatThrownBy(() -> p.addVariant(
                variant(p, "AUR-L", "250.00", "USD", Map.of("Ring Size", "L"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ZMW");

        // Same currency is fine.
        p.addVariant(variant(p, "AUR-L", "4700.00", "ZMW", Map.of("Ring Size", "L")));
        assertThat(p.variants()).hasSize(2);
    }

    @Test
    @DisplayName("the first variant sets the product's currency, whatever it is")
    void firstVariantEstablishesTheCurrency() {
        var p = ring();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(variant(p, "AUR-M", "250.00", "USD", Map.of("Ring Size", "M")));

        assertThat(p.currency()).contains("USD");
        assertThatThrownBy(() -> p.addVariant(
                variant(p, "AUR-L", "4700.00", "ZMW", Map.of("Ring Size", "L"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a product with no variants has no currency rather than a guessed one")
    void noVariantsNoCurrency() {
        assertThat(ring().currency()).isEmpty();
    }

    @Test
    @DisplayName("repricing a variant into another currency is refused for the same reason")
    void repricingCannotSplitTheCurrency() {
        var p = ring();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(variant(p, "AUR-M", "4500.00", "ZMW", Map.of("Ring Size", "M")));
        p.addVariant(variant(p, "AUR-L", "4700.00", "ZMW", Map.of("Ring Size", "L")));

        assertThatThrownBy(() -> p.repriceVariant(
                p.variants().getFirst().id(), new BigDecimal("250.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);

        // Repricing within the same currency is the normal case.
        p.repriceVariant(p.variants().getFirst().id(), new BigDecimal("4600.00"), "ZMW");
        assertThat(p.variants().getFirst().unitPrice()).isEqualByComparingTo("4600.00");
    }

    @Test
    @DisplayName("deactivating the last purchasable variant of an ACTIVE product is refused")
    void cannotStrandAnActiveProduct() {
        var p = ring();
        p.addVariant(variant(p, "AUR-001", "4500.00", "ZMW", Map.of()));
        p.publish();

        assertThatThrownBy(() -> p.deactivateVariant(p.variants().getFirst().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUR-001");

        assertThat(p.variants().getFirst().isActive()).isTrue();
        assertThat(p.status()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("repricing the last purchasable variant to zero is refused, exactly like deactivating it")
    void cannotStrandAnActiveProductByRepricing() {
        var p = ring();
        p.addVariant(variant(p, "AUR-001", "4500.00", "ZMW", Map.of()));
        p.publish();

        // deactivateVariant already refuses this, and its javadoc names the
        // outcome it is preventing: a product live on the storefront with
        // nothing to buy, which vanishes from the grid (the list query
        // requires a purchasable variant) while still answering on its own
        // URL. Repricing to zero reaches that identical state by another
        // door, because zero is not a price -- isPurchasable() requires
        // unitPrice > 0. The two mutators have to agree.
        assertThatThrownBy(() -> p.repriceVariant(
                p.variants().getFirst().id(), new BigDecimal("0.00"), "ZMW"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUR-001");

        assertThat(p.variants().getFirst().unitPrice()).isEqualByComparingTo("4500.00");
        assertThat(p.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(p.isPublishable()).isTrue();
    }

    @Test
    @DisplayName("repricing to zero is allowed while another purchasable variant remains")
    void repricingToZeroIsFineWithASpare() {
        var p = ring();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(variant(p, "AUR-M", "4500.00", "ZMW", Map.of("Ring Size", "M")));
        p.addVariant(variant(p, "AUR-L", "4700.00", "ZMW", Map.of("Ring Size", "L")));
        p.publish();

        p.repriceVariant(p.variants().getFirst().id(), new BigDecimal("0.00"), "ZMW");

        assertThat(p.isPublishable()).isTrue();
        // The zero-priced one drops out of the range rather than setting it.
        assertThat(p.lowestActivePrice()).contains(new BigDecimal("4700.00"));
    }

    @Test
    @DisplayName("a DRAFT product's last variant may be repriced to zero, because nothing is live")
    void draftCanBeRepricedToZero() {
        var p = ring();
        p.addVariant(variant(p, "AUR-001", "4500.00", "ZMW", Map.of()));

        p.repriceVariant(p.variants().getFirst().id(), new BigDecimal("0.00"), "ZMW");

        assertThat(p.variants().getFirst().unitPrice()).isEqualByComparingTo("0.00");
        assertThat(p.isPublishable()).isFalse();
    }

    @Test
    @DisplayName("deactivating a variant is fine while another purchasable one remains")
    void deactivatingIsFineWithASpare() {
        var p = ring();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(variant(p, "AUR-M", "4500.00", "ZMW", Map.of("Ring Size", "M")));
        p.addVariant(variant(p, "AUR-L", "4700.00", "ZMW", Map.of("Ring Size", "L")));
        p.publish();

        p.deactivateVariant(p.variants().getFirst().id());

        assertThat(p.variants().getFirst().isActive()).isFalse();
        assertThat(p.isPublishable()).isTrue();
        assertThat(p.status()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("a DRAFT product's last variant may be deactivated, because nothing is live")
    void draftCanBeStranded() {
        // The restriction exists to protect the storefront, so it applies only
        // while the product is on it. A draft is a work in progress.
        var p = ring();
        p.addVariant(variant(p, "AUR-001", "4500.00", "ZMW", Map.of()));

        p.deactivateVariant(p.variants().getFirst().id());

        assertThat(p.variants().getFirst().isActive()).isFalse();
        assertThat(p.isPublishable()).isFalse();
    }

    @Test
    @DisplayName("an ARCHIVED product's variants may be deactivated too")
    void archivedCanBeStranded() {
        var p = ring();
        p.addVariant(variant(p, "AUR-001", "4500.00", "ZMW", Map.of()));
        p.publish();
        p.archive();

        p.deactivateVariant(p.variants().getFirst().id());
        assertThat(p.variants().getFirst().isActive()).isFalse();
    }

    @Test
    @DisplayName("the price range a storefront card shows comes from active variants only")
    void priceRangeIgnoresInactiveVariants() {
        var p = ring();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(variant(p, "AUR-S", "4000.00", "ZMW", Map.of("Ring Size", "S")));
        p.addVariant(variant(p, "AUR-M", "4500.00", "ZMW", Map.of("Ring Size", "M")));
        p.addVariant(variant(p, "AUR-L", "5000.00", "ZMW", Map.of("Ring Size", "L")));
        p.publish();

        assertThat(p.lowestActivePrice()).contains(new BigDecimal("4000.00"));
        assertThat(p.highestActivePrice()).contains(new BigDecimal("5000.00"));

        p.deactivateVariant(p.variants().getFirst().id()); // the 4000 one

        // "from K4,000" on a card whose cheapest buyable option is 4,500 is a
        // price the customer cannot get.
        assertThat(p.lowestActivePrice()).contains(new BigDecimal("4500.00"));
    }

    @Test
    @DisplayName("a zero-priced variant is excluded from the range, as it is from publishability")
    void unpricedVariantsAreNotPartOfTheRange() {
        var p = ring();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(variant(p, "AUR-M", "0.00", "ZMW", Map.of("Ring Size", "M")));
        p.addVariant(variant(p, "AUR-L", "4700.00", "ZMW", Map.of("Ring Size", "L")));

        // Otherwise every migrated product would advertise "from K0.00".
        assertThat(p.lowestActivePrice()).contains(new BigDecimal("4700.00"));
    }
}
