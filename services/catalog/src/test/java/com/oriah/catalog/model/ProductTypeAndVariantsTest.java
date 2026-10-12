package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariants that make a product with variants coherent.
 *
 * <p>Separate from {@code ProductTest}, which covers the title/description/tag
 * rules that predate this phase.
 */
class ProductTypeAndVariantsTest {

    private static Product ring() {
        return new Product("Aurelia Solitaire Ring", "A single-stone solitaire.", List.of("rings"));
    }

    private static ProductVariant variant(Product p, String sku, String price, Map<String, String> options) {
        return new ProductVariant(p, sku, new BigDecimal(price), null, options);
    }

    @Nested
    @DisplayName("defaults for a newly created product")
    class Defaults {

        @Test
        @DisplayName("a new product is DRAFT, so nothing reaches the storefront before it is priced")
        void startsAsDraft() {
            assertThat(ring().status()).isEqualTo(ProductStatus.DRAFT);
        }

        @Test
        @DisplayName("a new product defaults to JEWELLERY, keeping existing admin clients working")
        void defaultsToJewellery() {
            // Deliberate: making productTypeCode a required request field
            // would be a breaking change to the admin create contract. A
            // default keeps it additive.
            assertThat(ring().productTypeCode()).isEqualTo(ProductTypeRegistry.JEWELLERY);
        }

        @Test
        @DisplayName("a new product gets a slug from its title, matching what V3 backfilled")
        void slugFromTitle() {
            assertThat(ring().slug()).isEqualTo("aurelia-solitaire-ring");
        }

        @Test
        @DisplayName("a new product has no attributes, no option axes, no variants, no images and no categories")
        void startsEmpty() {
            var p = ring();
            assertThat(p.attributes()).isEmpty();
            assertThat(p.optionNames()).isEmpty();
            assertThat(p.variants()).isEmpty();
            assertThat(p.images()).isEmpty();
            assertThat(p.categories()).isEmpty();
            assertThat(p.primaryCategory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("type and attributes")
    class TypeAndAttributes {

        @Test
        @DisplayName("a watch's attributes are validated against the watch type")
        void watchAttributesValidated() {
            var watch = new Product("Meridian Automatic 41", null, List.of());
            watch.applyType(ProductTypeRegistry.WATCH, Map.of(
                    "movement", "AUTOMATIC",
                    "caseDiameterMm", "41"));

            assertThat(watch.productTypeCode()).isEqualTo("WATCH");
            assertThat(watch.attributes())
                    .containsEntry("movement", "AUTOMATIC")
                    .containsEntry("caseDiameterMm", new BigDecimal("41"));
        }

        @Test
        @DisplayName("a jewellery attribute on a watch is rejected")
        void crossTypeAttributeRejected() {
            var watch = new Product("Meridian Automatic 41", null, List.of());

            assertThatThrownBy(() -> watch.applyType(ProductTypeRegistry.WATCH, Map.of(
                    "movement", "AUTOMATIC",
                    "caratWeight", "1.2")))
                    .isInstanceOf(AttributeValidationException.class);
        }

        @Test
        @DisplayName("switching type re-validates, so stale attributes cannot survive the change")
        void switchingTypeRevalidates() {
            var p = ring();
            p.applyType(ProductTypeRegistry.JEWELLERY, Map.of("metal", "18k Yellow Gold"));
            assertThat(p.attributes()).containsKey("metal");

            // Becoming a watch must not leave `metal` behind: it is not a
            // watch attribute, and the PDP would render it from the watch's
            // definition list, where it does not appear.
            assertThatThrownBy(() -> p.applyType(ProductTypeRegistry.WATCH,
                    Map.of("movement", "QUARTZ", "metal", "18k Yellow Gold")))
                    .isInstanceOf(AttributeValidationException.class);

            p.applyType(ProductTypeRegistry.WATCH, Map.of("movement", "QUARTZ"));
            assertThat(p.attributes()).containsOnlyKeys("movement");
        }

        @Test
        @DisplayName("an unknown type code is rejected")
        void unknownTypeRejected() {
            assertThatThrownBy(() -> ring().applyType("SOFA", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("variants")
    class Variants {

        @Test
        @DisplayName("a product with no option axes takes exactly one option-less variant")
        void oneOffPiece() {
            // The V3 backfill's shape, and a genuine case: a one-off piece
            // varies on nothing.
            var p = ring();
            p.addVariant(variant(p, "AUR-001", "4500.00", Map.of()));

            assertThat(p.variants()).hasSize(1);
            assertThat(p.variants().getFirst().options()).isEmpty();
        }

        @Test
        @DisplayName("a variant's option keys must match the product's declared axes exactly")
        void optionKeysMustMatchAxes() {
            var p = ring();
            p.declareOptionNames(List.of("Ring Size"));

            // Missing the declared axis.
            assertThatThrownBy(() -> p.addVariant(variant(p, "A", "4500.00", Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            // An axis the product never declared.
            assertThatThrownBy(() -> p.addVariant(
                    variant(p, "B", "4500.00", Map.of("Ring Size", "M", "Metal", "18k"))))
                    .isInstanceOf(IllegalArgumentException.class);
            // Right key set, so accepted.
            assertThat(p.addVariant(variant(p, "C", "4500.00", Map.of("Ring Size", "M")))).isNotNull();
        }

        @Test
        @DisplayName("the same option combination twice is rejected with a clear error, not a constraint violation")
        void duplicateCombinationRejected() {
            var p = ring();
            p.declareOptionNames(List.of("Ring Size"));
            p.addVariant(variant(p, "AUR-M", "4500.00", Map.of("Ring Size", "M")));

            assertThatThrownBy(() -> p.addVariant(variant(p, "AUR-M2", "4600.00", Map.of("Ring Size", "M"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("declaring new axes is refused while existing variants would stop matching")
        void cannotStrandExistingVariants() {
            var p = ring();
            p.declareOptionNames(List.of("Ring Size"));
            p.addVariant(variant(p, "AUR-M", "4500.00", Map.of("Ring Size", "M")));

            // Adding a "Metal" axis would leave the existing variant with a
            // key set that no longer matches, and the storefront could not
            // build a selector for it.
            assertThatThrownBy(() -> p.declareOptionNames(List.of("Ring Size", "Metal")))
                    .isInstanceOf(IllegalArgumentException.class);
            // Unchanged axes are fine, and so is reordering for display.
            p.declareOptionNames(List.of("Ring Size"));
            assertThat(p.optionNames()).containsExactly("Ring Size");
        }
    }

    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("a product with no variants cannot be published")
        void needsAVariant() {
            var p = ring();
            assertThat(p.isPublishable()).isFalse();
            assertThatThrownBy(p::publish).isInstanceOf(IllegalStateException.class);
            assertThat(p.status()).isEqualTo(ProductStatus.DRAFT);
        }

        @Test
        @DisplayName("a zero-priced variant does not make a product publishable")
        void zeroPriceIsNotAPrice() {
            // This is the rule that stops the V3 backfill from making the
            // whole existing catalogue free: every migrated variant is 0.00.
            var p = ring();
            p.addVariant(variant(p, "AUR-001", "0.00", Map.of()));

            assertThat(p.isPublishable()).isFalse();
            assertThatThrownBy(p::publish).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an inactive priced variant does not make a product publishable")
        void inactiveDoesNotCount() {
            var p = ring();
            var v = variant(p, "AUR-001", "4500.00", Map.of());
            p.addVariant(v);
            v.deactivate();

            assertThat(p.isPublishable()).isFalse();
        }

        @Test
        @DisplayName("one active priced variant is enough")
        void oneGoodVariantSuffices() {
            var p = ring();
            p.addVariant(variant(p, "AUR-001", "4500.00", Map.of()));

            assertThat(p.isPublishable()).isTrue();
            p.publish();
            assertThat(p.status()).isEqualTo(ProductStatus.ACTIVE);
        }

        @Test
        @DisplayName("a published product can be withdrawn to draft or archived")
        void canBeWithdrawn() {
            var p = ring();
            p.addVariant(variant(p, "AUR-001", "4500.00", Map.of()));
            p.publish();

            p.unpublishToDraft();
            assertThat(p.status()).isEqualTo(ProductStatus.DRAFT);
            p.archive();
            assertThat(p.status()).isEqualTo(ProductStatus.ARCHIVED);
        }
    }

    @Nested
    @DisplayName("categories")
    class Categories {

        @Test
        @DisplayName("the primary category must be one of the assigned categories")
        void primaryMustBeAMember() {
            var p = ring();
            var rings = new Category("Rings", null, null);
            var watches = new Category("Watches", null, null);

            // Otherwise the breadcrumb points at a category the product is
            // not in, which is worse than having no breadcrumb.
            assertThatThrownBy(() -> p.assignCategories(Set.of(rings), watches))
                    .isInstanceOf(IllegalArgumentException.class);

            p.assignCategories(Set.of(rings), rings);
            assertThat(p.primaryCategory()).contains(rings);
            assertThat(p.categories()).containsExactly(rings);
        }

        @Test
        @DisplayName("a product may have categories with no primary chosen")
        void primaryIsOptional() {
            var p = ring();
            var rings = new Category("Rings", null, null);

            p.assignCategories(Set.of(rings), null);
            assertThat(p.categories()).containsExactly(rings);
            assertThat(p.primaryCategory()).isEmpty();
        }

        @Test
        @DisplayName("reassigning categories away from the primary clears it rather than leaving it dangling")
        void reassignClearsAStrandedPrimary() {
            var p = ring();
            var rings = new Category("Rings", null, null);
            var gifts = new Category("Gifts", null, null);
            p.assignCategories(Set.of(rings), rings);

            p.assignCategories(Set.of(gifts), gifts);
            assertThat(p.primaryCategory()).contains(gifts);
            assertThat(p.categories()).containsExactly(gifts);
        }
    }

    @Nested
    @DisplayName("category hierarchy")
    class Hierarchy {

        @Test
        @DisplayName("a category knows its depth")
        void depthIsCounted() {
            var jewellery = new Category("Jewellery", null, null);
            var rings = new Category("Rings", null, jewellery);

            assertThat(jewellery.depth()).isZero();
            assertThat(rings.depth()).isEqualTo(1);
        }

        @Test
        @DisplayName("a cycle is refused when reparenting")
        void cyclesRefused() {
            var jewellery = new Category("Jewellery", null, null);
            var rings = new Category("Rings", null, jewellery);

            // jewellery -> rings -> jewellery. The CHECK constraint only
            // catches a category parented to itself, so this length has to be
            // caught here.
            assertThatThrownBy(() -> jewellery.reparent(rings))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jewellery.reparent(jewellery))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("moving to the root is allowed")
        void canMoveToRoot() {
            var jewellery = new Category("Jewellery", null, null);
            var rings = new Category("Rings", null, jewellery);

            rings.reparent(null);
            assertThat(rings.parent()).isEmpty();
            assertThat(rings.depth()).isZero();
        }
    }
}
