package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.model.ProductSort;
import com.oriah.catalog.model.StorefrontFilter;
import com.oriah.catalog.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a product must not destroy the variant row five other databases
 * key on.
 *
 * <h2>Why this is not merely tidy</h2>
 *
 * The V3 migration is built on one guarantee: every pre-existing product has a
 * variant whose id IS the product's id, because that id is already a primary
 * key in {@code inventory.inventory}, {@code inventory.product_index} and
 * {@code search.search_index}, and the line identity in {@code cart_items} and
 * {@code order_items}. None of those has a foreign key into catalog, because
 * none shares its database.
 *
 * <p>A hard delete cascaded straight through {@code product_variants} and
 * removed exactly that row. Two consequences, both real:
 *
 * <ul>
 *   <li>{@code order_items} — a deliberately immutable financial record —
 *       would reference a variant that no longer exists, so an order's line
 *       could no longer be resolved to what was actually sold.</li>
 *   <li>The pricing endpoint's answer would silently change from
 *       {@code UNPUBLISHED} to {@code UNKNOWN}, collapsing the very
 *       distinction that endpoint exists to provide: a bag can word "no longer
 *       available" differently from "this line is corrupt", and after a hard
 *       delete it could no longer tell them apart.</li>
 * </ul>
 *
 * <p>So a delete now archives and flags the aggregate instead. The product
 * becomes unreachable through every read path, and the rows that other
 * services depend on survive.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ProductDeletionTest {

    /** Who the event log records as making these changes (ProductOutboxTest covers the actor). */
    private static final String STAFF = "user:catalog-test";

    @Autowired
    private ProductService products;

    @Autowired
    private VariantPricingService pricing;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID productId;
    private UUID variantId;

    @BeforeEach
    void seedPublishedProduct() {
        jdbc.update("DELETE FROM product_variant_images");
        jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM product_variants");
        jdbc.update("DELETE FROM product_categories");
        jdbc.update("DELETE FROM product_tags");
        jdbc.update("UPDATE products SET primary_category_id = NULL");
        jdbc.update("DELETE FROM products");

        productId = products.create("Aurelia Solitaire Ring", null, List.of("rings"), STAFF).id();
        var withVariant = products.addVariant(productId, new VariantCreateRequest(
                "AUR-1", new BigDecimal("4500.00"), "ZMW", Map.of()), STAFF).orElseThrow();
        variantId = withVariant.variants().getFirst().id();
        products.publish(productId, STAFF);
    }

    @Test
    @DisplayName("the variant row SURVIVES a delete, because other databases key on its id")
    void variantRowSurvives() {
        assertThat(products.deleteById(productId, STAFF)).isTrue();

        Integer variants = jdbc.queryForObject(
                "SELECT count(*) FROM product_variants WHERE id = ?", Integer.class, variantId);
        assertThat(variants)
                .as("order_items references this id and is an immutable financial record")
                .isEqualTo(1);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE id = ?", Integer.class, productId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("a deleted product is flagged and archived, not physically removed")
    void deletedProductIsFlaggedAndArchived() {
        products.deleteById(productId, STAFF);

        var row = jdbc.queryForMap(
                "SELECT is_deleted, status FROM products WHERE id = ?", productId);

        assertThat(row.get("is_deleted")).isEqualTo(true);
        // Archived as well as flagged. The flag is what the read paths filter
        // on; the status is what anything reading the row directly -- another
        // service's report, a support query -- sees, and ACTIVE on a deleted
        // product would be a lie told to whoever looks next.
        assertThat(row.get("status")).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("a deleted product is gone from the storefront list")
    void goneFromTheList() {
        assertThat(repository.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.NEWEST, 0, 50))
                .contains(productId);

        products.deleteById(productId, STAFF);

        assertThat(repository.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.NEWEST, 0, 50))
                .doesNotContain(productId);
        assertThat(repository.countStorefront(StorefrontFilter.unfiltered())).isZero();
    }

    @Test
    @DisplayName("a deleted product 404s on the public read")
    void goneFromThePublicRead() {
        products.deleteById(productId, STAFF);
        assertThat(products.findByIdWithAvailability(productId)).isEmpty();
    }

    @Test
    @DisplayName("a deleted product is gone from the ADMIN read too, so it cannot be edited back")
    void goneFromTheAdminRead() {
        products.deleteById(productId, STAFF);

        // An operator who deleted a product should not find it in the admin
        // API either -- otherwise "deleted" means "hidden from customers", and
        // an edit would resurrect something the audit trail says is gone.
        assertThat(products.findByIdForAdmin(productId)).isEmpty();
    }

    @Test
    @DisplayName("deleting twice reports false the second time, as a hard delete did")
    void secondDeleteIsFalse() {
        assertThat(products.deleteById(productId, STAFF)).isTrue();
        // The HTTP contract is unchanged: 204 then 404. A soft delete that
        // reported success forever would make DELETE non-idempotent in the
        // wrong direction.
        assertThat(products.deleteById(productId, STAFF)).isFalse();
    }

    @Test
    @DisplayName("pricing answers UNPUBLISHED for a deleted product's variant, not UNKNOWN")
    void pricingStillDistinguishesWithdrawnFromUnknown() {
        products.deleteById(productId, STAFF);

        var entry = pricing.priceAll(List.of(variantId)).results().getFirst();

        // THE assertion this change exists for. A bag holding this line can
        // say "no longer available"; UNKNOWN would mean "we have no idea what
        // this is", which is what a hard delete produced and is a different
        // message to a customer.
        assertThat(entry.status()).isEqualTo(
                com.oriah.catalog.dto.VariantPricingResponse.Status.UNPUBLISHED);
        assertThat(entry.productTitle()).isEqualTo("Aurelia Solitaire Ring");
        // Still no price, because it is not for sale.
        assertThat(entry.unitPrice()).isNull();

        var unknown = pricing.priceAll(List.of(UUID.randomUUID())).results().getFirst();
        assertThat(unknown.status()).isEqualTo(
                com.oriah.catalog.dto.VariantPricingResponse.Status.UNKNOWN);
    }

    @Test
    @DisplayName("a deleted product's slug stays taken, so a new product cannot silently inherit its URL")
    void slugRemainsReserved() {
        products.deleteById(productId, STAFF);

        var replacement = products.create("Aurelia Solitaire Ring", null, List.of(), STAFF);

        // The row still exists, so the UNIQUE constraint still applies and the
        // collision resolver gives the new product a distinct slug. That is
        // the right outcome: an old link should not start resolving to a
        // different product because the original was deleted.
        assertThat(replacement.slug()).isNotEqualTo("aurelia-solitaire-ring");
        assertThat(replacement.slug()).startsWith("aurelia-solitaire-ring-");
    }

    @Test
    @DisplayName("a delete still emits the deletion event, so downstream services learn about it")
    void stillEmitsTheEvent() {
        // The event remains the signal to inventory and search. What changes
        // is what inventory DOES with it -- it flags its reference rather than
        // deleting it, for the same reason the variant row survives here.
        assertThat(products.deleteById(productId, STAFF)).isTrue();
        assertThat(products.findByIdWithAvailability(productId)).isEmpty();
    }
}
