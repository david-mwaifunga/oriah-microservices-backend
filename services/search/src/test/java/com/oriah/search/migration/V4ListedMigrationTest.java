package com.oriah.search.migration;

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
 * V4 adds {@code listed} to the search index: only an ACTIVE, SELLABLE product
 * may be searched, and the index now records that per row.
 *
 * <p>The rows that matter are the ones that exist BEFORE V4, so Flyway is driven
 * directly (as catalog's V3 and V5 tests do): migrate to V3, write rows and the
 * bookkeeping a running consumer leaves behind, then migrate the rest.
 *
 * <h2>Why every old row comes out unlisted</h2>
 *
 * Before V4 the index stored no status, so nothing can say which of these rows
 * is published. Listing them all would keep every draft searchable, which is the
 * defect V4 exists to end; listing none fails closed. A row is listed again by
 * the next snapshot of its product -- catalog writes one on every change, and
 * saving a product with no changes writes one too -- because each snapshot is a
 * whole product with a higher seq than anything already applied.
 *
 * <p>The bookkeeping ({@code processed_event}, {@code processed_aggregate}) must
 * come through untouched: it is what stops an old event being applied twice, so
 * an edit to it would be a way to lose or repeat events.
 */
@Testcontainers
class V4ListedMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static final UUID OLD_ROW = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @BeforeAll
    static void migrateWithAnIndexFromBeforeV4() throws Exception {
        migrateTo(MigrationVersion.fromVersion("3"));

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO search_index (variant_id, title, description, tags)
                    VALUES ('%s', 'Old Aurelia Ring', '18k gold', 'rings gold')""".formatted(OLD_ROW));
            s.executeUpdate("""
                    INSERT INTO processed_aggregate (consumer, aggregate_type, aggregate_id, last_seq)
                    VALUES ('search', 'product', '%s', 7)""".formatted(OLD_ROW));
            s.executeUpdate("""
                    INSERT INTO processed_event (consumer, event_id)
                    VALUES ('search', '%s')""".formatted(UUID.randomUUID()));
        }

        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("a row from before V4 is kept, and comes out unlisted because nothing says it is published")
    void oldRowsAreKeptAndUnlisted() throws Exception {
        assertThat(stringOf("SELECT title FROM search_index WHERE variant_id = '" + OLD_ROW + "'"))
                .isEqualTo("Old Aurelia Ring");
        assertThat(stringOf("SELECT listed::text FROM search_index WHERE variant_id = '" + OLD_ROW + "'"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("listed is required, so no writer can leave it undecided")
    void listedIsRequired() throws Exception {
        assertThat(stringOf("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_name = 'search_index' AND column_name = 'listed'"""))
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("what the consumer remembers having applied is untouched")
    void bookkeepingIsUntouched() throws Exception {
        assertThat(stringOf("SELECT last_seq::text FROM processed_aggregate WHERE aggregate_id = '" + OLD_ROW + "'"))
                .isEqualTo("7");
        assertThat(stringOf("SELECT count(*)::text FROM processed_event WHERE consumer = 'search'"))
                .isEqualTo("1");
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
