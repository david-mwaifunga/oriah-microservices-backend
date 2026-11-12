package com.oriah.catalog.config;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.catalog.model.ProductTypeRegistry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A product type is deliberately split in half: the CODE is a row in
 * {@code product_types} (so {@code products.product_type_code} has a real
 * foreign key and the admin dropdown has something to enumerate), while the
 * ATTRIBUTE DEFINITIONS live in {@link ProductTypeRegistry} (so one edit
 * updates validation, the admin form and the PDP spec table together).
 *
 * <p>A split like that only stays safe if the halves are checked against each
 * other, which is what this rule is for. Both directions of drift are real
 * bugs with distinct causes:
 *
 * <ul>
 *   <li>A row the registry does not know means products of that type are
 *       validated against NO rules at all — an attribute allow-list that
 *       allows everything.</li>
 *   <li>A registry code with no row means creating such a product violates
 *       the foreign key, so the failure surfaces as a constraint violation on
 *       save rather than as the missing seed migration it actually is.</li>
 * </ul>
 *
 * <p>Checked at startup rather than per request, because the correct response
 * to either is "this build must not serve traffic", and a per-request check
 * would turn a deployment mistake into an intermittent 500.
 */
class ProductTypeConsistencyTest {

    @Test
    @DisplayName("matching halves pass")
    void matchingSetsPass() {
        assertThatCode(() -> ProductTypeConsistency.verify(
                Set.of("JEWELLERY", "WATCH"), Set.of("JEWELLERY", "WATCH")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the real registry agrees with what the migrations seeded")
    void theActualRegistryMatchesTheActualMigration() {
        // V3 inserts JEWELLERY and WATCH, and V5 adds PACKAGING. This is the
        // assertion that would catch someone adding a type to Java without the
        // migration, or the reverse, at build time rather than at boot.
        assertThatCode(() -> ProductTypeConsistency.verify(
                Set.of("JEWELLERY", "WATCH", "PACKAGING"), ProductTypeRegistry.knownCodes()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a database row the registry does not know fails, naming the code")
    void unknownDatabaseCodeFails() {
        assertThatThrownBy(() -> ProductTypeConsistency.verify(
                Set.of("JEWELLERY", "WATCH", "SOFA"), Set.of("JEWELLERY", "WATCH")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOFA");
    }

    @Test
    @DisplayName("a registry code with no database row fails, naming the code")
    void missingDatabaseRowFails() {
        assertThatThrownBy(() -> ProductTypeConsistency.verify(
                Set.of("JEWELLERY"), Set.of("JEWELLERY", "WATCH")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WATCH");
    }

    @Test
    @DisplayName("both kinds of drift at once are reported together")
    void bothDirectionsReported() {
        assertThatThrownBy(() -> ProductTypeConsistency.verify(
                Set.of("JEWELLERY", "SOFA"), Set.of("JEWELLERY", "WATCH")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOFA")
                .hasMessageContaining("WATCH");
    }

    @Test
    @DisplayName("an empty product_types table fails rather than being treated as 'nothing to check'")
    void emptyDatabaseFails() {
        // This is what a service pointed at a database whose migrations have
        // not run looks like. Passing silently would let it serve requests
        // that then fail on every product write.
        assertThatThrownBy(() -> ProductTypeConsistency.verify(
                Set.of(), ProductTypeRegistry.knownCodes()))
                .isInstanceOf(IllegalStateException.class);
    }
}
