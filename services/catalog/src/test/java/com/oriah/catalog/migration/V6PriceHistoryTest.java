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
 * V6 turns a price into dated history, gives a product the default box it goes out in, and counts the times it has been
 * unpublished.
 *
 * <p>Driven through Flyway directly, like {@link V5ProductKindTest}, because the interesting rows are the ones that exist BEFORE
 * the migration: a ring that was already priced must come out with a history that says so (one row, marked as migrated, so nobody
 * mistakes it for a price somebody set against a cost), and a variant that was never priced must come out with no history at all.
 */
@Testcontainers
class V6PriceHistoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static final UUID PRICED_RING = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID UNPRICED_CUFF = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID DOLLAR_WATCH = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @BeforeAll
    static void migrateWithRowsFromBeforeV6() throws Exception {
        migrateTo(MigrationVersion.fromVersion("5"));

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO products (id, title, slug, product_type_code, status, kind)
                    VALUES ('%s', 'Priced Ring', 'priced-ring', 'JEWELLERY', 'ACTIVE', 'SELLABLE'),
                           ('%s', 'Unpriced Cuff', 'unpriced-cuff', 'JEWELLERY', 'DRAFT', 'SELLABLE'),
                           ('%s', 'Dollar Watch', 'dollar-watch', 'WATCH', 'ACTIVE', 'SELLABLE')
                    """.formatted(PRICED_RING, UNPRICED_CUFF, DOLLAR_WATCH));
            // Each product gets a variant by hand, as an older service would have written it.
            s.executeUpdate("""
                    INSERT INTO product_variants (id, product_id, sku, unit_price, currency, options, option_digest, updated_at)
                    VALUES ('%1$s', '%1$s', 'RING-OLD', 1500.00, 'ZMW', '{}'::jsonb, 'd1', '2026-10-01 08:30:00'),
                           ('%2$s', '%2$s', 'CUFF-OLD', 0.00, 'ZMW', '{}'::jsonb, 'd2', NULL),
                           ('%3$s', '%3$s', 'WATCH-OLD', 99.50, 'USD', '{}'::jsonb, 'd3', NULL)
                    ON CONFLICT (id) DO UPDATE SET sku = EXCLUDED.sku, unit_price = EXCLUDED.unit_price,
                        currency = EXCLUDED.currency, updated_at = EXCLUDED.updated_at
                    """.formatted(PRICED_RING, UNPRICED_CUFF, DOLLAR_WATCH));
        }

        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("every variant that was priced gets one history row, marked as migrated; one that was never priced gets none")
    void backfillsThePricedVariantsOnly() throws Exception {
        assertThat(longOf("SELECT count(*) FROM variant_price WHERE variant_id = '" + PRICED_RING + "'")).isEqualTo(1);
        assertThat(longOf("SELECT count(*) FROM variant_price WHERE variant_id = '" + DOLLAR_WATCH + "'")).isEqualTo(1);
        assertThat(longOf("SELECT count(*) FROM variant_price WHERE variant_id = '" + UNPRICED_CUFF + "'")).isZero();
    }

    @Test
    @DisplayName("a migrated row keeps the price and the currency it had (a legacy dollar price stays a dollar price), and says nobody assessed it against a cost")
    void migratedRowsKeepWhatTheyHad() throws Exception {
        assertThat(stringOf("SELECT unit_price::text FROM variant_price WHERE variant_id = '" + PRICED_RING + "'")).isEqualTo("1500.00");
        assertThat(stringOf("SELECT currency FROM variant_price WHERE variant_id = '" + DOLLAR_WATCH + "'")).isEqualTo("USD");
        assertThat(stringOf("SELECT source FROM variant_price WHERE variant_id = '" + PRICED_RING + "'")).isEqualTo("MIGRATED");
        assertThat(stringOf("SELECT assessment FROM variant_price WHERE variant_id = '" + PRICED_RING + "'")).isEqualTo("NOT_ASSESSED");
        assertThat(stringOf("SELECT set_by FROM variant_price WHERE variant_id = '" + PRICED_RING + "'")).isEqualTo("system:migration");
    }

    @Test
    @DisplayName("a migrated row takes effect when the variant was last changed (read as UTC), or from the migration when that is unknown")
    void migratedRowsAreDated() throws Exception {
        assertThat(stringOf("SELECT to_char(effective_from AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM variant_price WHERE variant_id = '" + PRICED_RING + "'"))
                .isEqualTo("2026-10-01 08:30:00");
        assertThat(longOf("SELECT count(*) FROM variant_price WHERE variant_id = '" + DOLLAR_WATCH + "' AND effective_from IS NOT NULL AND recorded_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("the history is append-only: the database refuses an update, a delete and a truncate")
    void historyIsAppendOnly() {
        assertThatThrownBy(() -> execute("UPDATE variant_price SET unit_price = 1.00"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM variant_price"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("TRUNCATE variant_price"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a price is above zero, and an assessment is one the code knows")
    void priceAndAssessmentAreChecked() {
        assertThatThrownBy(() -> insertPrice("0.00", "OK", null))
                .isInstanceOf(SQLException.class).hasMessageContaining("variant_price_price_positive");
        assertThatThrownBy(() -> insertPrice("10.00", "FINE", null))
                .isInstanceOf(SQLException.class).hasMessageContaining("variant_price_assessment_known");
    }

    @Test
    @DisplayName("a price saved BELOW cost, or in the wrong currency, cannot be written without a reason of at least five characters")
    void aReasonIsRequiredWhenTheCostWasNotCovered() throws Exception {
        for (String assessment : new String[] {"BELOW", "CURRENCY_MISMATCH"}) {
            assertThatThrownBy(() -> insertPrice("10.00", assessment, null))
                    .isInstanceOf(SQLException.class).hasMessageContaining("variant_price_reason_when_flagged");
            assertThatThrownBy(() -> insertPrice("10.00", assessment, "  ab "))
                    .isInstanceOf(SQLException.class).hasMessageContaining("variant_price_reason_when_flagged");
            insertPrice("10.00", assessment, "clearance of the old range");
        }
        insertPrice("10.00", "OK", null);
        insertPrice("10.00", "NO_COST_KNOWN", null);
    }

    @Test
    @DisplayName("two rows from the same instant are allowed and keep the order they were written in (seq)")
    void sameInstantRowsKeepTheirWriteOrder() throws Exception {
        UUID variant = UUID.randomUUID();
        execute("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment)
                VALUES ('%s', '%s', 15000.00, 'ZMW', '2026-11-12 09:00:00+00', now(), 'user:kc-maya', 'SET', 'OK'),
                       ('%s', '%s', 1500.00, 'ZMW', '2026-11-12 09:00:00+00', now(), 'user:kc-maya', 'SET', 'OK')
                """.formatted(UUID.randomUUID(), variant, UUID.randomUUID(), variant));

        assertThat(stringOf("SELECT unit_price::text FROM variant_price WHERE variant_id = '" + variant + "' ORDER BY effective_from, seq DESC LIMIT 1"))
                .isEqualTo("1500.00");
    }

    @Test
    @DisplayName("a product has a default box that may be none, and an unpublish count that starts at zero, for every product that existed before")
    void productColumns() throws Exception {
        assertThat(stringOf("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'products' AND column_name = 'default_packaging_variant_id'"))
                .isEqualTo("YES");
        assertThat(stringOf("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'products' AND column_name = 'unpublish_count'"))
                .isEqualTo("NO");
        assertThat(longOf("SELECT count(*) FROM products WHERE unpublish_count <> 0 OR default_packaging_variant_id IS NOT NULL")).isZero();
        assertThatThrownBy(() -> execute("UPDATE products SET unpublish_count = -1 WHERE id = '" + PRICED_RING + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("products_unpublish_count_nonnegative");
    }

    @Test
    @DisplayName("the currency column is VARCHAR(3), which is what Hibernate's schema validation expects of a String")
    void currencyIsVarchar() throws Exception {
        assertThat(stringOf("SELECT data_type FROM information_schema.columns WHERE table_name = 'variant_price' AND column_name = 'currency'"))
                .isEqualTo("character varying");
    }

    private static void insertPrice(String price, String assessment, String reason) throws Exception {
        execute("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment, reason)
                VALUES ('%s', '%s', %s, 'ZMW', now(), now(), 'user:kc-maya', 'SET', '%s', %s)
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), price, assessment, reason == null ? "NULL" : "'" + reason + "'"));
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

    private static long longOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
