package com.oriah.catalog.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5 gives every catalog entry a {@code kind}: SELLABLE (what Oriah sells) or
 * PACKAGING (what a sale goes out in), and has the database itself refuse a
 * PACKAGING row that is ACTIVE.
 *
 * <p>Driven through Flyway directly, like {@link V3BackfillTest}, because the
 * interesting rows are the ones that exist BEFORE the migration: a normally
 * started service has run every migration before a test can insert anything.
 * A published ring from before V5 must come out SELLABLE and still ACTIVE --
 * the new constraint must not strand the live catalogue.
 */
@Testcontainers
class V5ProductKindTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    /** A published ring and a draft, both written before V5 exists. */
    private static final UUID LIVE_RING = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DRAFT_CUFF = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @BeforeAll
    static void migrateWithRowsFromBeforeV5() throws Exception {
        migrateTo(MigrationVersion.fromVersion("4"));

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO products (id, title, slug, product_type_code, status)
                    VALUES ('%s', 'Live Ring', 'live-ring', 'JEWELLERY', 'ACTIVE'),
                           ('%s', 'Draft Cuff', 'draft-cuff', 'JEWELLERY', 'DRAFT')
                    """.formatted(LIVE_RING, DRAFT_CUFF));
        }

        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("every product from before V5 is SELLABLE, and a published one stays published")
    void existingProductsAreSellable() throws Exception {
        assertThat(stringOf("SELECT kind FROM products WHERE id = '" + LIVE_RING + "'")).isEqualTo("SELLABLE");
        assertThat(stringOf("SELECT kind FROM products WHERE id = '" + DRAFT_CUFF + "'")).isEqualTo("SELLABLE");
        assertThat(stringOf("SELECT status FROM products WHERE id = '" + LIVE_RING + "'")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("kind is required and has no default, so a writer must say what it is making")
    void kindIsRequiredWithNoDefault() throws Exception {
        assertThat(stringOf("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_name = 'products' AND column_name = 'kind'"""))
                .isEqualTo("NO");
        assertThat(stringOf("""
                SELECT column_default FROM information_schema.columns
                 WHERE table_name = 'products' AND column_name = 'kind'"""))
                .isNull();
    }

    @Test
    @DisplayName("a PACKAGING row may be DRAFT or ARCHIVED")
    void packagingMayBeDraftOrArchived() throws Exception {
        insertProduct("PACKAGING", "PACKAGING", "DRAFT");
        insertProduct("PACKAGING", "PACKAGING", "ARCHIVED");
    }

    @Test
    @DisplayName("the database refuses a PACKAGING row that is ACTIVE, whatever wrote it")
    void databaseRefusesActivePackaging() {
        assertThatThrownBy(() -> insertProduct("PACKAGING", "PACKAGING", "ACTIVE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("products_packaging_never_listed");
    }

    @Test
    @DisplayName("the database refuses publishing a PACKAGING row that already exists")
    void databaseRefusesPublishingExistingPackaging() throws Exception {
        UUID box = insertProduct("PACKAGING", "PACKAGING", "DRAFT");

        assertThatThrownBy(() -> execute("UPDATE products SET status = 'ACTIVE' WHERE id = '" + box + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("products_packaging_never_listed");
    }

    @Test
    @DisplayName("the database refuses a kind it does not know")
    void databaseRefusesUnknownKind() {
        assertThatThrownBy(() -> insertProduct("SERVICE", "JEWELLERY", "DRAFT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("products_kind_known");
    }

    @Test
    @DisplayName("PACKAGING is a product type the database knows, and it is not a type for sale")
    void packagingTypeIsSeeded() throws Exception {
        assertThat(stringOf("SELECT display_name FROM product_types WHERE code = 'PACKAGING'"))
                .isEqualTo("Packaging");
    }

    @Test
    @DisplayName("a PACKAGING entry must be typed PACKAGING: not jewellery, not a watch")
    void databaseRefusesPackagingOfAnotherType() {
        assertThatThrownBy(() -> insertProduct("PACKAGING", "JEWELLERY", "DRAFT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("products_packaging_type_matches_kind");
        assertThatThrownBy(() -> insertProduct("PACKAGING", "WATCH", "DRAFT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("products_packaging_type_matches_kind");
    }

    @Test
    @DisplayName("a SELLABLE entry cannot be typed PACKAGING")
    void databaseRefusesSellableTypedAsPackaging() {
        assertThatThrownBy(() -> insertProduct("SELLABLE", "PACKAGING", "DRAFT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("products_packaging_type_matches_kind");
    }

    private static UUID insertProduct(String kind, String typeCode, String status) throws Exception {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO products (id, title, slug, product_type_code, status, kind)
                VALUES ('%s', 'Item %s', 'item-%s', '%s', '%s', '%s')
                """.formatted(id, id, id, typeCode, status, kind));
        return id;
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

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

    private static String stringOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
