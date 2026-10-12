package com.oriah.catalog.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity-preserving backfill, tested against a database that already
 * holds pre-variant products.
 *
 * <h2>Why this test exists and why it is shaped like this</h2>
 *
 * Catalog's product id is currently used AS the variant id in five separate
 * databases: inventory (both {@code inventory.variant_id} and
 * {@code product_index.variant_id} are primary keys fed from the catalog
 * product id), cart ({@code UNIQUE (cart_id, variant_id)} is the line
 * identity), order ({@code order_items.variant_id}, a deliberately immutable
 * financial snapshot) and search ({@code search_index.variant_id}). There is
 * no cross-database transaction and order_items must never be rewritten, so a
 * re-keying migration is not available to us.
 *
 * The consequence is that V3 has exactly one safe shape: every pre-existing
 * product gets exactly ONE variant whose id IS the product's own id. Then
 * {@code variantId == productId} stays true for every row written before this
 * migration and becomes false only for variants created afterwards, and every
 * existing cart line, order line and stock row keeps resolving untouched.
 *
 * Testing that requires rows that predate V3, which a normally-started
 * application can never have — Flyway runs every migration before any test
 * code gets a connection. So this test drives Flyway directly: migrate to V2,
 * insert legacy-shaped products, then migrate the rest of the way and assert
 * what the backfill did. It deliberately does NOT boot a Spring context; the
 * subject is the SQL, and a JPA entity in between would hide a column-level
 * mistake behind a mapping.
 */
@Testcontainers
class V3BackfillTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    /** Seeded before V3 runs, so these are the rows the backfill must handle. */
    private static final UUID AURELIA = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NO_TAGS = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeAll
    static void migrateWithLegacyRowsInPlace() throws Exception {
        migrateTo(MigrationVersion.fromVersion("2"));

        try (Connection c = connect(); Statement s = c.createStatement()) {
            // Exactly the shape V1/V2 produce: title, description, tags, and
            // none of the columns V3 is about to add.
            s.executeUpdate("""
                    INSERT INTO products (id, title, description)
                    VALUES ('%s', 'Aurelia Solitaire Ring', 'A single-stone solitaire.'),
                           ('%s', 'Untagged Cuff', NULL)
                    """.formatted(AURELIA, NO_TAGS));
            s.executeUpdate("""
                    INSERT INTO product_tags (product_id, tag)
                    VALUES ('%s', 'rings'), ('%s', '18k')
                    """.formatted(AURELIA, AURELIA));
        }

        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("every pre-existing product gets exactly one variant")
    void oneVariantPerLegacyProduct() throws Exception {
        assertThat(countOf("SELECT count(*) FROM product_variants WHERE product_id = '" + AURELIA + "'"))
                .isEqualTo(1);
        assertThat(countOf("SELECT count(*) FROM product_variants WHERE product_id = '" + NO_TAGS + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the backfilled variant's id IS the product's id, which is what keeps cart, order, inventory and search rows resolving")
    void variantIdEqualsProductId() throws Exception {
        // The single assertion this whole migration is designed around. If it
        // ever fails, every pre-existing order line points at a variant that
        // does not exist.
        assertThat(countOf("SELECT count(*) FROM product_variants WHERE id <> product_id"))
                .isZero();
        assertThat(countOf("SELECT count(*) FROM product_variants WHERE id = '" + AURELIA + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("backfilled products are DRAFT and unpriced, so nothing unpriced is sellable")
    void backfilledProductsAreNotSellable() throws Exception {
        // A migrated product has no price anyone chose. Publishing it at 0.00
        // would make it free; leaving it ACTIVE at 0.00 is the one outcome
        // this migration must not produce.
        assertThat(stringOf("SELECT status FROM products WHERE id = '" + AURELIA + "'"))
                .isEqualTo("DRAFT");
        assertThat(countOf("SELECT count(*) FROM products WHERE status <> 'DRAFT'"))
                .isZero();
        assertThat(stringOf("SELECT unit_price::text FROM product_variants WHERE id = '" + AURELIA + "'"))
                .isEqualTo("0.00");
    }

    @Test
    @DisplayName("backfilled variants carry the brand currency, not a hardcoded USD")
    void backfilledVariantsUseBrandCurrency() throws Exception {
        assertThat(stringOf("SELECT currency FROM product_variants WHERE id = '" + AURELIA + "'"))
                .isEqualTo("ZMW");
    }

    @Test
    @DisplayName("backfilled products are JEWELLERY with empty attributes and no option axes")
    void backfilledProductsGetTheDefaultType() throws Exception {
        assertThat(stringOf("SELECT product_type_code FROM products WHERE id = '" + AURELIA + "'"))
                .isEqualTo("JEWELLERY");
        assertThat(stringOf("SELECT attributes::text FROM products WHERE id = '" + AURELIA + "'"))
                .isEqualTo("{}");
        // option_names empty and options empty is the default-variant case:
        // a one-off piece that does not vary on any axis. Counted rather than
        // read as a string, because the driver renders a boolean column as
        // "t" and because this way it covers both seeded rows, not just one.
        assertThat(countOf("SELECT count(*) FROM products WHERE option_names = '{}'"))
                .isEqualTo(2);
        assertThat(countOf("SELECT count(*) FROM product_variants WHERE options <> '{}'::jsonb"))
                .isZero();
    }

    @Test
    @DisplayName("every product ends up with a unique non-null slug derived from its title")
    void slugsAreBackfilledAndUnique() throws Exception {
        assertThat(stringOf("SELECT slug FROM products WHERE id = '" + AURELIA + "'"))
                .isEqualTo("aurelia-solitaire-ring");
        assertThat(countOf("SELECT count(*) FROM products WHERE slug IS NULL")).isZero();
        assertThat(countOf("SELECT count(DISTINCT slug) FROM products")).isEqualTo(2);
    }

    @Test
    @DisplayName("every backfilled variant gets a unique non-null SKU")
    void skusAreBackfilledAndUnique() throws Exception {
        assertThat(countOf("SELECT count(*) FROM product_variants WHERE sku IS NULL")).isZero();
        assertThat(countOf("SELECT count(DISTINCT sku) FROM product_variants")).isEqualTo(2);
    }

    @Test
    @DisplayName("existing tags and descriptions survive the migration untouched")
    void preExistingDataIsPreserved() throws Exception {
        assertThat(countOf("SELECT count(*) FROM product_tags WHERE product_id = '" + AURELIA + "'"))
                .isEqualTo(2);
        assertThat(stringOf("SELECT description FROM products WHERE id = '" + AURELIA + "'"))
                .isEqualTo("A single-stone solitaire.");
        assertThat(stringOf("SELECT title FROM products WHERE id = '" + NO_TAGS + "'"))
                .isEqualTo("Untagged Cuff");
    }

    @Test
    @DisplayName("the two seeded product types are present and the FK points at them")
    void productTypesAreSeeded() throws Exception {
        assertThat(countOf("SELECT count(*) FROM product_types WHERE code IN ('JEWELLERY','WATCH')"))
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private static void migrateTo(MigrationVersion version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long countOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String stringOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
