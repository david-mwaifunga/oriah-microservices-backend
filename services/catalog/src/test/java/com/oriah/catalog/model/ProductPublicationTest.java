package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6: publishing is its own step after pricing, and a product can be taken off sale again. Taking it off sale is counted
 * (inventory releases the boxes it set aside for the product when the count goes up), and a product has a default box it goes out
 * in, which may be none.
 */
class ProductPublicationTest {

    private static Product pricedRing() {
        Product ring = new Product("Aurelia Ring");
        ring.addVariant(new ProductVariant(ring, "RING-1", new BigDecimal("1500.00"), null, Map.of()));
        return ring;
    }

    @Test
    @DisplayName("a published product can be unpublished: it goes back to DRAFT, and the unpublish is counted")
    void unpublishGoesBackToDraftAndCounts() {
        Product ring = pricedRing();
        ring.publish();
        assertThat(ring.unpublishCount()).isZero();

        ring.unpublish();

        assertThat(ring.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(ring.unpublishCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("it can be published and unpublished again, and the count only ever goes up")
    void countOnlyGrows() {
        Product ring = pricedRing();
        ring.publish();
        ring.unpublish();
        ring.publish();
        ring.unpublish();

        assertThat(ring.unpublishCount()).isEqualTo(2);
        assertThat(ring.status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    @DisplayName("a product that is not published cannot be unpublished (409), and a refused unpublish is not counted")
    void onlyAPublishedProductCanBeUnpublished() {
        Product draft = pricedRing();

        assertThatThrownBy(draft::unpublish).isInstanceOf(IllegalStateException.class).hasMessageContaining("not published");
        assertThat(draft.unpublishCount()).isZero();
    }

    @Test
    @DisplayName("a product starts with no default box")
    void noDefaultBoxToStartWith() {
        assertThat(new Product("Ring").defaultPackagingVariantId()).isNull();
    }

    @Test
    @DisplayName("a default box can be chosen, changed and cleared")
    void chooseChangeAndClear() {
        Product ring = pricedRing();
        UUID small = UUID.randomUUID();
        UUID large = UUID.randomUUID();

        ring.chooseDefaultPackaging(small);
        assertThat(ring.defaultPackagingVariantId()).isEqualTo(small);
        ring.chooseDefaultPackaging(large);
        assertThat(ring.defaultPackagingVariantId()).isEqualTo(large);
        ring.chooseDefaultPackaging(null);
        assertThat(ring.defaultPackagingVariantId()).isNull();
    }

    @Test
    @DisplayName("packaging has no default box of its own: a box is not boxed")
    void packagingHasNoDefaultBox() {
        Product box = new Product(UUID.randomUUID(), "Gift box", null, List.of(), ProductKind.PACKAGING);

        assertThatThrownBy(() -> box.chooseDefaultPackaging(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("packaging");
        box.chooseDefaultPackaging(null);   // clearing nothing is not an error
    }
}
