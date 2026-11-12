package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3: a catalog entry is either something Oriah SELLS or the packaging
 * (a box, a pouch, a ribbon) a sale goes out in. Packaging is bought, stocked
 * and costed like any other item, so it needs a catalog entry for a
 * consignment line to point at -- but it is never listed on the storefront.
 *
 * <p>The rule that keeps it off the storefront is one invariant: a PACKAGING
 * product can never be ACTIVE. Every shopper-facing read and the price the
 * cart, checkout and order services trust all start from "is the product
 * ACTIVE", so holding this one rule holds them all.
 */
class ProductKindTest {

    private static Product packaging(String title) {
        return new Product(UUID.randomUUID(), title, null, List.of(), ProductKind.PACKAGING);
    }

    private static ProductVariant pricedVariant(Product product, String sku, String price) {
        return new ProductVariant(product, sku, new BigDecimal(price), null, Map.of());
    }

    @Test
    @DisplayName("a product made the ordinary way is SELLABLE, so every existing caller keeps working")
    void defaultsToSellable() {
        assertThat(new Product("Aurelia Solitaire Ring").kind()).isEqualTo(ProductKind.SELLABLE);
        assertThat(new Product("Ring", "desc", List.of("rings")).kind()).isEqualTo(ProductKind.SELLABLE);
    }

    @Test
    @DisplayName("a product can be made as packaging, and it starts as a DRAFT like any other")
    void canBeCreatedAsPackaging() {
        Product box = packaging("Gift box, medium");

        assertThat(box.kind()).isEqualTo(ProductKind.PACKAGING);
        assertThat(box.status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    @DisplayName("packaging is typed PACKAGING, never jewellery, and a sellable product is never typed PACKAGING")
    void kindAndTypeAgree() {
        assertThat(packaging("Gift box").productTypeCode()).isEqualTo(ProductTypeRegistry.PACKAGING);
        assertThat(new Product("Ring").productTypeCode()).isEqualTo(ProductTypeRegistry.JEWELLERY);
    }

    @Test
    @DisplayName("packaging cannot be re-typed as a watch or as jewellery")
    void packagingCannotBeRetyped() {
        Product box = packaging("Gift box");

        assertThatThrownBy(() -> box.applyType(ProductTypeRegistry.JEWELLERY, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packaging");
        assertThatThrownBy(() -> box.applyType(ProductTypeRegistry.WATCH, Map.of("movement", "QUARTZ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packaging");
        assertThat(box.productTypeCode()).isEqualTo(ProductTypeRegistry.PACKAGING);
    }

    @Test
    @DisplayName("a sellable product cannot be typed PACKAGING")
    void sellableCannotBeTypedPackaging() {
        Product ring = new Product("Ring");

        assertThatThrownBy(() -> ring.applyType(ProductTypeRegistry.PACKAGING, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packaging");
        assertThat(ring.productTypeCode()).isEqualTo(ProductTypeRegistry.JEWELLERY);
    }

    @Test
    @DisplayName("packaging declares no spec fields, so any attribute sent for it is refused")
    void packagingHasNoSpecFields() {
        assertThat(ProductTypeRegistry.definitionsFor(ProductTypeRegistry.PACKAGING)).isEmpty();
        assertThatThrownBy(() -> packaging("Gift box").applyType(
                        ProductTypeRegistry.PACKAGING, Map.of("metal", "gold")))
                .isInstanceOf(AttributeValidationException.class);
    }

    @Test
    @DisplayName("packaging has no selling price: a variant above zero is refused, zero is fine")
    void packagingVariantsAreFree() {
        Product box = packaging("Gift box");

        assertThatThrownBy(() -> box.addVariant(pricedVariant(box, "BOX-1", "5.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
        box.addVariant(pricedVariant(box, "BOX-1", "0.00"));
        assertThat(box.variants()).hasSize(1);
    }

    @Test
    @DisplayName("repricing a packaging variant above zero is refused")
    void packagingVariantCannotBeRepriced() {
        Product box = packaging("Gift box");
        ProductVariant variant = box.addVariant(pricedVariant(box, "BOX-1", "0.00"));

        assertThatThrownBy(() -> box.repriceVariant(variant.id(), new BigDecimal("5.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
        box.repriceVariant(variant.id(), BigDecimal.ZERO, null);
    }

    @Test
    @DisplayName("a null kind is refused rather than guessed")
    void refusesNullKind() {
        assertThatThrownBy(() -> new Product(UUID.randomUUID(), "Box", null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
    }

    @Test
    @DisplayName("packaging is never publishable, even if it somehow held a variant priced above zero")
    void packagingIsNeverPublishable() {
        Product box = packaging("Gift box, medium");
        box.addVariant(pricedVariant(box, "BOX-M", "0.00"));
        // The price rule keeps a real one at zero; forcing a price in below it
        // proves publishing is refused on its own account and not only because
        // a zero price is "not purchasable".
        ReflectionTestUtils.setField(box.variants().get(0), "unitPrice", new BigDecimal("25.00"));

        assertThat(box.variants().get(0).isPurchasable()).isTrue();
        assertThat(box.isPublishable()).isFalse();
    }

    @Test
    @DisplayName("publishing packaging is refused, says why, and leaves it a DRAFT")
    void publishingPackagingIsRefused() {
        Product box = packaging("Gift box, medium");
        box.addVariant(pricedVariant(box, "BOX-M", "0.00"));
        ReflectionTestUtils.setField(box.variants().get(0), "unitPrice", new BigDecimal("25.00"));

        assertThatThrownBy(box::publish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("packaging");
        assertThat(box.status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    @DisplayName("a sellable product is still published exactly as before")
    void sellableStillPublishes() {
        Product ring = new Product("Aurelia Solitaire Ring");
        ring.addVariant(pricedVariant(ring, "RING-1", "4500.00"));

        ring.publish();

        assertThat(ring.status()).isEqualTo(ProductStatus.ACTIVE);
    }
}
