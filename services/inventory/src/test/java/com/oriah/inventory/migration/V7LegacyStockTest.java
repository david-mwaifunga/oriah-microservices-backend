package com.oriah.inventory.migration;

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
 * V7 gives stock a cost. Stock that exists BEFORE V7 came from the retired "create a stock row with an on-hand
 * count" endpoint: it has no cost basis, and inventing one would be wrong, so V7 removes those rows (only developer
 * seed data existed; a go-live database starts empty). From then on a stock row can only come from a received
 * consignment, which brings its lots with it, so "what the lots hold equals the count" is true from the first row.
 *
 * <p>Flyway is driven directly, as catalog's and search's migration tests do: migrate to V6, write rows, migrate the
 * rest. What the product consumer remembers having applied must come through untouched.
 */
@Testcontainers
class V7LegacyStockTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static final UUID SEEDED = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID TITLE_ONLY = UUID.fromString("88888888-8888-4888-8888-888888888888");

    @BeforeAll
    static void migrateWithStockFromBeforeV7() throws Exception {
        migrateTo(MigrationVersion.fromVersion("6"));
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO inventory (variant_id, on_hand, reserved) VALUES ('%s', 10, 3)".formatted(SEEDED));
            s.executeUpdate("INSERT INTO product_index (variant_id, title, product_id) VALUES ('%s', 'Old Aurelia Ring', '%s')"
                    .formatted(TITLE_ONLY, UUID.randomUUID()));
            s.executeUpdate("INSERT INTO processed_aggregate (consumer, aggregate_type, aggregate_id, last_seq) VALUES ('inventory', 'product', '%s', 4)"
                    .formatted(TITLE_ONLY));
        }
        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("stock that has no cost is removed: its count was only ever a seed, and a cost cannot be invented")
    void uncostedStockIsRemoved() throws Exception {
        assertThat(stringOf("SELECT count(*) FROM inventory")).isEqualTo("0");
    }

    @Test
    @DisplayName("what the product consumer remembers, and the product titles, are untouched")
    void bookkeepingIsUntouched() throws Exception {
        assertThat(stringOf("SELECT title FROM product_index WHERE variant_id = '" + TITLE_ONLY + "'")).isEqualTo("Old Aurelia Ring");
        assertThat(stringOf("SELECT last_seq::text FROM processed_aggregate WHERE aggregate_id = '" + TITLE_ONLY + "'")).isEqualTo("4");
    }

    @Test
    @DisplayName("a stock row now says whether it is goods or packaging, and new rows must say")
    void aStockRowHasAKind() throws Exception {
        assertThat(stringOf("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_name = 'inventory' AND column_name = 'kind'""")).isEqualTo("NO");
    }

    // ----- helpers -----

    private static void migrateTo(MigrationVersion version) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(version).load().migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String stringOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
