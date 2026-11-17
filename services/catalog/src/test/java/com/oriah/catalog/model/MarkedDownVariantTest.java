package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The listing of one returned piece sold again at a lower price (Phase 12), as the product sees it: one of its variants, with the options of
 * the variant it came from, kept apart from the ordinary ones wherever the PRODUCT's place in the shop is decided.
 */
class MarkedDownVariantTest {

    private static Product ringAt(String price) {
        Product ring = new Product("Aurelia Ring");
        ring.addVariant(new ProductVariant(ring, "RING-1", new BigDecimal(price), null, Map.of()));
        return ring;
    }

    private static ProductVariant markDown(Product product, String price) {
        ProductVariant source = product.variants().get(0);
        ProductVariant child = ProductVariant.markedDown(UUID.randomUUID(), source, source.sku() + "-R-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID());
        product.addMarkedDown(child);
        child.reprice(new BigDecimal(price), child.currency());
        return child;
    }

    @Test
    @DisplayName("it copies its source's options and currency, starts unpriced, and says where it came from and which piece it is for")
    void whatItIs() {
        Product ring = new Product("Sized Ring");
        ring.declareOptionNames(java.util.List.of("Ring Size"));
        ProductVariant source = ring.addVariant(new ProductVariant(ring, "SZ-M", new BigDecimal("1500.00"), null, Map.of("Ring Size", "M")));
        UUID id = UUID.randomUUID();
        UUID piece = UUID.randomUUID();

        ProductVariant child = ring.addMarkedDown(ProductVariant.markedDown(id, source, "SZ-M-R-1", piece));

        assertThat(child.id()).isEqualTo(id);
        assertThat(child.isMarkedDown()).isTrue();
        assertThat(child.condition()).isEqualTo(VariantCondition.MARKED_DOWN);
        assertThat(child.sourceVariantId()).isEqualTo(source.id());
        assertThat(child.markdownPieceId()).isEqualTo(piece);
        assertThat(child.options()).isEqualTo(source.options());
        assertThat(child.optionDigest()).isEqualTo(source.optionDigest());
        assertThat(child.unitPrice()).isEqualByComparingTo("0.00");
        assertThat(child.currency()).isEqualTo("ZMW");
        assertThat(source.isMarkedDown()).isFalse();
        assertThat(source.sourceVariantId()).isNull();
        assertThat(ring.variants()).hasSize(2);
    }

    @Test
    @DisplayName("it is never the product's 'from' or 'to' price")
    void neverTheFromPrice() {
        Product ring = ringAt("1500.00");
        markDown(ring, "900.00");

        assertThat(ring.lowestActivePrice()).hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("1500.00"));
        assertThat(ring.highestActivePrice()).hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("1500.00"));
    }

    @Test
    @DisplayName("a product whose only priced variant is a marked-down piece cannot be published")
    void itDoesNotMakeAProductPublishable() {
        Product ring = ringAt("0.00");
        markDown(ring, "900.00");

        assertThat(ring.isPublishable()).isFalse();
        assertThatThrownBy(ring::publish).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the last ordinary variant of a published product cannot be withdrawn or priced to nothing because a marked-down piece is still for sale")
    void itDoesNotLetTheLastOrdinaryVariantGo() {
        Product ring = ringAt("1500.00");
        markDown(ring, "900.00");
        ring.publish();
        UUID ordinary = ring.variants().get(0).id();

        assertThatThrownBy(() -> ring.deactivateVariant(ordinary)).isInstanceOf(IllegalStateException.class).hasMessageContaining("last purchasable");
        assertThatThrownBy(() -> ring.repriceVariant(ordinary, new BigDecimal("0.00"), "ZMW")).isInstanceOf(IllegalStateException.class);
        // The marked-down piece itself may go: it carries nothing.
        ring.deactivateVariant(ring.variants().get(1).id());
        assertThat(ring.isPublishable()).isTrue();
    }

    @Test
    @DisplayName("an ordinary variant may still not repeat an option combination, though marked-down pieces of it do")
    void ordinaryVariantsStayUnique() {
        Product ring = ringAt("1500.00");
        markDown(ring, "900.00");
        markDown(ring, "800.00");

        assertThatThrownBy(() -> ring.addVariant(new ProductVariant(ring, "RING-2", new BigDecimal("0.00"), null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("what it is made from: an ordinary variant, of a product that is sold, through its own door")
    void whatItIsMadeFrom() {
        Product ring = ringAt("1500.00");
        ProductVariant child = markDown(ring, "900.00");

        assertThatThrownBy(() -> ProductVariant.markedDown(UUID.randomUUID(), child, "X-R-1", UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ring.addVariant(ProductVariant.markedDown(UUID.randomUUID(), ring.variants().get(0), "X-R-2", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ring.addMarkedDown(new ProductVariant(ring, "RING-3", new BigDecimal("0.00"), null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        Product other = ringAt("1500.00");
        assertThatThrownBy(() -> other.addMarkedDown(ProductVariant.markedDown(UUID.randomUUID(), ring.variants().get(0), "X-R-3", UUID.randomUUID())))
                .as("a marked-down piece belongs to its source's product").isInstanceOf(IllegalArgumentException.class);
    }
}
