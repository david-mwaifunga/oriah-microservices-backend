package com.oriah.catalog.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.catalog.dto.ProductImageResponse;
import com.oriah.catalog.dto.VariantResponse;
import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Category;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductImage;
import com.oriah.catalog.model.ProductTypeRegistry;
import com.oriah.catalog.model.ProductVariant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the storefront actually receives.
 *
 * <p>Separate from {@code ProductMapperTest}, which characterises the
 * pre-variant wire format and must keep passing — the additions here are
 * additive precisely so that it does.
 *
 * <p>Each assertion corresponds to something the storefront currently fakes:
 * a hardcoded {@code unitPrice={100}} in the product detail page, a gradient
 * div with the title's first letter where a photograph belongs, four empty
 * placeholder divs posing as thumbnails, and a truncated UUID labelled "SKU".
 */
class ProductWireFormatTest {

    private static Product watchWithVariants() {
        var watch = new Product("Meridian Dive 300", "A dive watch.", List.of("watches"));
        watch.applyType(ProductTypeRegistry.WATCH, Map.of(
                "movement", "AUTOMATIC", "waterResistanceM", "300"));
        watch.declareOptionNames(List.of("Strap"));
        watch.addVariant(new ProductVariant(watch, "MER-STEEL", new BigDecimal("9000.00"), "ZMW",
                Map.of("Strap", "Steel")));
        watch.addVariant(new ProductVariant(watch, "MER-RUBBER", new BigDecimal("8500.00"), "ZMW",
                Map.of("Strap", "Rubber")));
        var watches = new Category("Watches", null, null);
        watch.assignCategories(Set.of(watches), watches);
        watch.publish();
        return watch;
    }

    @Test
    @DisplayName("a list row carries the price range, so a card can show 'from K8,500'")
    void listRowCarriesPriceRange() {
        var r = ProductMapper.toResponse(watchWithVariants());

        assertThat(r.priceFrom()).isEqualByComparingTo("8500.00");
        assertThat(r.priceTo()).isEqualByComparingTo("9000.00");
        assertThat(r.currency()).isEqualTo("ZMW");
    }

    @Test
    @DisplayName("a list row carries slug, type and status")
    void listRowCarriesIdentityFields() {
        var r = ProductMapper.toResponse(watchWithVariants());

        assertThat(r.slug()).isEqualTo("meridian-dive-300");
        assertThat(r.productType()).isEqualTo("WATCH");
        assertThat(r.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a list row carries categories, so the grid can show a category chip")
    void listRowCarriesCategories() {
        var r = ProductMapper.toResponse(watchWithVariants());

        assertThat(r.categories()).extracting("slug").containsExactly("watches");
        assertThat(r.primaryCategory()).isNotNull();
        assertThat(r.primaryCategory().slug()).isEqualTo("watches");
        // Children are not populated on a product's categories: the nav tree
        // is its own endpoint, and nesting them here would carry the whole
        // taxonomy on every card.
        assertThat(r.categories().getFirst().children()).isNull();
    }

    @Test
    @DisplayName("a list row omits variants and attributes, which only a detail page renders")
    void listRowOmitsDetailOnlyFields() {
        var r = ProductMapper.toResponse(watchWithVariants());

        assertThat(r.variants()).isNull();
        assertThat(r.attributes()).isNull();
        assertThat(r.availability()).isNull();
    }

    @Test
    @DisplayName("a detail response carries every variant with its own price, SKU and options")
    void detailCarriesVariants() {
        var r = ProductMapper.toResponse(
                new com.oriah.catalog.service.ProductWithAvailability(
                        watchWithVariants(), Availability.IN_STOCK));

        assertThat(r.variants()).hasSize(2);
        // Ordered by position, which is insertion order -- so the selector
        // renders in the order the admin arranged it.
        VariantResponse steel = r.variants().getFirst();
        assertThat(steel.sku()).isEqualTo("MER-STEEL");
        assertThat(steel.unitPrice()).isEqualByComparingTo("9000.00");
        assertThat(steel.currency()).isEqualTo("ZMW");
        assertThat(steel.options()).containsEntry("Strap", "Steel");
        assertThat(steel.active()).isTrue();
        // The id a client posts to the cart. Not the product id.
        assertThat(steel.id()).isNotEqualTo(r.id());
    }

    @Test
    @DisplayName("a detail response carries the type's attributes, so the spec table is real")
    void detailCarriesAttributes() {
        var r = ProductMapper.toResponse(
                new com.oriah.catalog.service.ProductWithAvailability(
                        watchWithVariants(), Availability.IN_STOCK));

        // A watch declares movement and water resistance. The current PDP
        // renders "Solid 18k gold" for this product.
        assertThat(r.attributes())
                .containsEntry("movement", "AUTOMATIC")
                .containsEntry("waterResistanceM", new BigDecimal("300"));
        assertThat(r.productType()).isEqualTo("WATCH");
    }

    @Test
    @DisplayName("images are exposed as relative URLs with dimensions, not as bytes or absolute links")
    void imagesAreRelativeUrls() {
        var watch = watchWithVariants();
        watch.addImage(new ProductImage(watch, "products/x/abc.jpg", "a".repeat(64),
                "image/jpeg", 1600, 1600, 240_000L, "Front view"));

        var r = ProductMapper.toResponse(watch);

        assertThat(r.images()).hasSize(1);
        ProductImageResponse image = r.images().getFirst();
        // Relative, so the same string works in the browser and during
        // server-side rendering without naming a host.
        assertThat(image.url())
                .isEqualTo("/api/v1/product-images/" + image.id())
                .startsWith("/api/v1/");
        assertThat(image.altText()).isEqualTo("Front view");
        // Published so the grid can reserve space instead of reflowing.
        assertThat(image.width()).isEqualTo(1600);
        assertThat(image.height()).isEqualTo(1600);
    }

    @Test
    @DisplayName("a product with no variants reports no price rather than zero")
    void noVariantsNoPrice() {
        // Otherwise every product the variants migration touched would
        // advertise "from K0.00" until someone priced it.
        var bare = new Product("Unpriced Piece", null, List.of());
        var r = ProductMapper.toResponse(bare);

        assertThat(r.priceFrom()).isNull();
        assertThat(r.priceTo()).isNull();
        assertThat(r.currency()).isNull();
    }

    @Test
    @DisplayName("a deactivated variant is excluded from the price range but still listed on detail")
    void inactiveVariantsAffectPriceButRemainVisible() {
        var watch = watchWithVariants();
        var rubber = watch.variants().stream()
                .filter(v -> v.sku().equals("MER-RUBBER")).findFirst().orElseThrow();
        watch.deactivateVariant(rubber.id());

        var summary = ProductMapper.toResponse(watch);
        assertThat(summary.priceFrom()).isEqualByComparingTo("9000.00");

        // Still present on detail, flagged inactive, so the selector can show
        // the option as unavailable rather than silently dropping it -- a
        // missing size reads as "we never made it".
        var detail = ProductMapper.toResponse(
                new com.oriah.catalog.service.ProductWithAvailability(watch, Availability.IN_STOCK));
        assertThat(detail.variants()).hasSize(2);
        assertThat(detail.variants()).filteredOn(v -> !v.active()).hasSize(1);
    }
}
