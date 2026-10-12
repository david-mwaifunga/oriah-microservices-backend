package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry is the single definition of what a product type's specs are,
 * and it has three consumers that must not disagree: server-side validation,
 * the admin form, and the PDP spec table.
 *
 * <p>It is the direct answer to the defect that a jewellery PDP and a watch
 * PDP currently render the same hardcoded markup, so a watch page claims to be
 * "Solid 18k gold". A watch declares movement and water resistance; a ring
 * declares metal and carat weight; neither can carry the other's fields.
 */
class ProductTypeRegistryTest {

    private static Map<String, Object> attrs(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A complete, valid watch — the happy path the admin form submits. */
    private static Map<String, Object> validWatch() {
        return attrs(
                "movement", "AUTOMATIC",
                "caseDiameterMm", "41",
                "caseMaterial", "Stainless Steel",
                "strapMaterial", "Alligator Leather");
    }

    @Nested
    @DisplayName("the catalogue of types")
    class Types {

        @Test
        @DisplayName("exactly the two types V3 seeded are known")
        void knownCodesMatchTheMigration() {
            // V3 seeds product_types with these two and the products FK points
            // at that table, so a code here with no row there (or the reverse)
            // is a startup-time failure rather than a runtime surprise.
            assertThat(ProductTypeRegistry.knownCodes())
                    .containsExactlyInAnyOrder("JEWELLERY", "WATCH");
        }

        @Test
        @DisplayName("a watch declares watch specs and a ring declares jewellery specs")
        void eachTypeHasItsOwnFields() {
            var watchKeys = ProductTypeRegistry.definitionsFor(ProductTypeRegistry.WATCH)
                    .stream().map(AttributeDefinition::key).toList();
            var jewelleryKeys = ProductTypeRegistry.definitionsFor(ProductTypeRegistry.JEWELLERY)
                    .stream().map(AttributeDefinition::key).toList();

            assertThat(watchKeys).contains("movement", "caseDiameterMm", "waterResistanceM");
            assertThat(jewelleryKeys).contains("metal", "metalPurity", "caratWeight");
            // The two sets must not bleed, or the PDP is back to showing a
            // watch's case diameter on a ring.
            assertThat(watchKeys).doesNotContainAnyElementsOf(jewelleryKeys);
        }

        @Test
        @DisplayName("definitions come back in declaration order, because the form and spec table render them in order")
        void orderIsStable() {
            var first = ProductTypeRegistry.definitionsFor(ProductTypeRegistry.WATCH);
            var second = ProductTypeRegistry.definitionsFor(ProductTypeRegistry.WATCH);
            assertThat(first).containsExactlyElementsOf(second);
        }

        @Test
        @DisplayName("an unknown type code is rejected rather than treated as having no specs")
        void unknownTypeRejected() {
            // Silently returning an empty definition list would let a product
            // with a bogus type validate against nothing at all.
            assertThatThrownBy(() -> ProductTypeRegistry.definitionsFor("SOFA"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SOFA");
            assertThatThrownBy(() -> ProductTypeRegistry.validate("SOFA", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a complete watch validates")
        void happyPath() {
            assertThat(ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, validWatch()))
                    .containsEntry("movement", "AUTOMATIC");
        }

        @Test
        @DisplayName("an attribute the type does not declare is rejected, naming the key")
        void unknownAttributeRejected() {
            var withExtra = validWatch();
            withExtra.put("caratWeight", "1.2"); // a jewellery field on a watch

            assertThatThrownBy(() -> ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, withExtra))
                    .isInstanceOf(AttributeValidationException.class)
                    .hasMessageContaining("caratWeight");
        }

        @Test
        @DisplayName("a missing required attribute is rejected, naming the key")
        void missingRequiredRejected() {
            var withoutMovement = validWatch();
            withoutMovement.remove("movement");

            assertThatThrownBy(() -> ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, withoutMovement))
                    .isInstanceOf(AttributeValidationException.class)
                    .hasMessageContaining("movement");
        }

        @Test
        @DisplayName("an ENUM value outside allowedValues is rejected")
        void enumValueMustBeAllowed() {
            var bad = validWatch();
            bad.put("movement", "SOLAR");

            assertThatThrownBy(() -> ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, bad))
                    .isInstanceOf(AttributeValidationException.class)
                    .hasMessageContaining("movement");
        }

        @Test
        @DisplayName("EVERY bad field is reported, not just the first")
        void allErrorsCollected() {
            // The watch form renders five fields at once. Failing fast would
            // make an operator submit five times to find five problems.
            var bad = attrs(
                    "movement", "SOLAR",          // not an allowed value
                    "caseDiameterMm", "wide",     // not a number
                    "caseMaterial", "Steel",
                    "strapMaterial", "Leather",
                    "bezelColour", "blue");       // not a declared attribute

            assertThatThrownBy(() -> ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, bad))
                    .isInstanceOf(AttributeValidationException.class)
                    .satisfies(e -> assertThat(((AttributeValidationException) e).fieldErrors())
                            .extracting(Map.Entry::getKey)
                            .contains("movement", "caseDiameterMm", "bezelColour"));
        }

        @Test
        @DisplayName("a NUMBER arrives from the form as a string and is coerced to BigDecimal")
        void numericStringsAreCoerced() {
            // JSON from an HTML form sends "41", not 41. Rejecting that would
            // make the admin UI responsible for typing every spec field.
            var out = ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, validWatch());

            assertThat(out.get("caseDiameterMm"))
                    .isInstanceOf(BigDecimal.class)
                    .isEqualTo(new BigDecimal("41"));
        }

        @Test
        @DisplayName("a NUMBER that is not numeric is rejected")
        void nonNumericRejected() {
            var bad = validWatch();
            bad.put("caseDiameterMm", "about forty");

            assertThatThrownBy(() -> ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, bad))
                    .isInstanceOf(AttributeValidationException.class)
                    .hasMessageContaining("caseDiameterMm");
        }

        @Test
        @DisplayName("an optional attribute may be absent, and a null is treated as absent")
        void optionalMayBeOmitted() {
            // waterResistanceM is optional: a dress watch legitimately has none.
            var out = ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, validWatch());
            assertThat(out).doesNotContainKey("waterResistanceM");

            var withNull = validWatch();
            withNull.put("waterResistanceM", null);
            assertThat(ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, withNull))
                    .doesNotContainKey("waterResistanceM");
        }

        @Test
        @DisplayName("a jewellery product with no attributes at all validates, because none of its fields are required")
        void jewelleryNeedsNothing() {
            // This is the shape the V3 backfill produced for every pre-existing
            // product: type JEWELLERY, attributes '{}'. If it did not validate,
            // the entire migrated catalogue would be unsaveable.
            assertThat(ProductTypeRegistry.validate(ProductTypeRegistry.JEWELLERY, Map.of()))
                    .isEmpty();
            assertThat(ProductTypeRegistry.validate(ProductTypeRegistry.JEWELLERY, null))
                    .isEmpty();
        }

        @Test
        @DisplayName("validated output keeps only declared keys, so nothing unvalidated reaches the JSONB column")
        void outputIsWhitelisted() {
            var out = ProductTypeRegistry.validate(ProductTypeRegistry.WATCH, validWatch());
            assertThat(out.keySet())
                    .allSatisfy(k -> assertThat(
                            ProductTypeRegistry.definitionsFor(ProductTypeRegistry.WATCH)
                                    .stream().map(AttributeDefinition::key).toList())
                            .contains(k));
        }
    }
}
