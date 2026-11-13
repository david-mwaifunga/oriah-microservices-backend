package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.repository.ProductRepository;

/**
 * Products for the pricing tests, made straight through the domain and the repository: a piece or a box that starts unpriced,
 * which is how every variant starts now. Nothing here prices anything: only the pricing endpoint does that, and that is what the
 * tests exercise.
 */
public final class PricingFixtures {

    /** A product and its one variant. */
    public record Piece(UUID productId, UUID variantId) {
    }

    private final ProductRepository products;

    public PricingFixtures(ProductRepository products) {
        this.products = products;
    }

    /** A sellable piece with one unpriced variant, a DRAFT. */
    public Piece ring(String title) {
        Product product = new Product(title + " " + UUID.randomUUID());
        ProductVariant variant = new ProductVariant(product, "SKU-" + UUID.randomUUID(), BigDecimal.ZERO, "ZMW", Map.of());
        product.addVariant(variant);
        products.saveAndFlush(product);
        return new Piece(product.id(), variant.id());
    }

    /** A box: packaging, with one variant that is never priced. */
    public Piece box(String title) {
        Product product = new Product(UUID.randomUUID(), title + " " + UUID.randomUUID(), null, List.of(), ProductKind.PACKAGING);
        ProductVariant variant = new ProductVariant(product, "BOX-" + UUID.randomUUID(), BigDecimal.ZERO, "ZMW", Map.of());
        product.addVariant(variant);
        products.saveAndFlush(product);
        return new Piece(product.id(), variant.id());
    }

    /**
     * Clears the catalogue, for a test whose answer depends on EVERYTHING that is priced (the below-cost list). The price history is
     * append-only and is left alone: it is keyed by variant, and nothing reads it for a variant that no longer exists.
     */
    public static void wipeCatalogue(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM product_variant_images");
        jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM product_variants");
        jdbc.update("DELETE FROM product_categories");
        jdbc.update("DELETE FROM product_tags");
        jdbc.update("UPDATE products SET primary_category_id = NULL");
        jdbc.update("DELETE FROM products");
    }

    /** Puts a price on a variant directly, as an older version of the service would have: for tests of what is READ, not of how it is saved. */
    public static void priceDirectly(org.springframework.jdbc.core.JdbcTemplate jdbc, UUID variantId, String price) {
        jdbc.update("UPDATE product_variants SET unit_price = ?::numeric WHERE id = ?", price, variantId);
    }

    public void defaultBox(UUID productId, UUID boxVariantId) {
        Product product = products.findById(productId).orElseThrow();
        product.chooseDefaultPackaging(boxVariantId);
        products.saveAndFlush(product);
    }
}
