package com.oriah.promotion.migration;

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
 * V3 retires the single-use discount CODE (a whole-order discount that staff could type) and makes a promotion what the client runs: a scope
 * (one product, one category with its sub-categories, or the whole shop), dates (Lusaka calendar days, the last day included) and a discount per
 * piece (a Kwacha amount off each piece, or a percentage), applied by itself. A promotion is never edited: an edit ends the old one and starts a
 * new one that says which it replaces, and a stop is a dated fact, so any past instant has exactly one answer.
 */
@Testcontainers
class V3ScopedPromotionsTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @BeforeAll
    static void migrateWithACodeFromBeforeV3() throws Exception {
        migrateTo(MigrationVersion.fromVersion("2"));
        execute("INSERT INTO promotions (id, code, type, amount) VALUES ('%s', 'WELCOME15', 'PERCENTAGE', 15.00)".formatted(UUID.randomUUID()));
        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("the old discount codes are gone (developer data only): a code is not how the client runs a promotion")
    void oldCodesAreGone() throws Exception {
        assertThat(stringOf("SELECT count(*)::text FROM information_schema.tables WHERE table_name = 'promotions'")).isEqualTo("0");
        assertThat(stringOf("SELECT count(*)::text FROM promotion WHERE name = 'WELCOME15'")).as("the old code was not carried into the new shape").isEqualTo("0");
    }

    @Test
    @DisplayName("a promotion is for one product, one category or the whole shop; a product or category promotion names it, a shop one does not")
    void scope() throws Exception {
        insert("SHOP", null, "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31");
        insert("PRODUCT", UUID.randomUUID(), "FIXED", "50.00", "2026-12-01", "2026-12-31");
        insert("CATEGORY", UUID.randomUUID(), "PERCENTAGE", "5.00", "2026-12-01", "2026-12-31");

        assertThatThrownBy(() -> insert("SHOP", UUID.randomUUID(), "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31")).isInstanceOf(SQLException.class)
                .hasMessageContaining("promotion_scope_names_its_target");
        assertThatThrownBy(() -> insert("PRODUCT", null, "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31")).isInstanceOf(SQLException.class)
                .hasMessageContaining("promotion_scope_names_its_target");
        assertThatThrownBy(() -> insert("WORLD", null, "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31")).isInstanceOf(SQLException.class)
                .hasMessageContaining("promotion_scope_known");
    }

    @Test
    @DisplayName("a discount is above zero; a percentage is at most 100; the last day is not before the first")
    void theDiscountAndTheDates() {
        assertThatThrownBy(() -> insert("SHOP", null, "PERCENTAGE", "0.00", "2026-12-01", "2026-12-31")).isInstanceOf(SQLException.class)
                .hasMessageContaining("promotion_amount_check");
        assertThatThrownBy(() -> insert("SHOP", null, "PERCENTAGE", "100.01", "2026-12-01", "2026-12-31")).isInstanceOf(SQLException.class)
                .hasMessageContaining("promotion_percentage_at_most_100");
        assertThatThrownBy(() -> insert("SHOP", null, "BOGOF", "10.00", "2026-12-01", "2026-12-31")).isInstanceOf(SQLException.class)
                .hasMessageContaining("promotion_discount_type_known");
        assertThatThrownBy(() -> insert("SHOP", null, "FIXED", "10.00", "2026-12-31", "2026-12-01")).isInstanceOf(SQLException.class)
                .hasMessageContaining("promotion_last_day_not_before_first");
    }

    @Test
    @DisplayName("a promotion is never edited: only a stop may be written, once; and it is never deleted")
    void aPromotionIsHistory() throws Exception {
        UUID id = insert("SHOP", null, "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31");

        assertThatThrownBy(() -> execute("UPDATE promotion SET amount = 20.00 WHERE id = '" + id + "'")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> execute("UPDATE promotion SET ends_on = '2027-01-31' WHERE id = '" + id + "'")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> execute("DELETE FROM promotion WHERE id = '" + id + "'")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> execute("TRUNCATE promotion")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");

        execute("UPDATE promotion SET stopped_at = now(), stopped_by = 'user:kc-maya' WHERE id = '" + id + "'");
        assertThatThrownBy(() -> execute("UPDATE promotion SET stopped_at = now() + interval '1 day' WHERE id = '" + id + "'")).isInstanceOf(SQLException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("a replacement names the promotion it replaces")
    void replacement() throws Exception {
        UUID first = insert("SHOP", null, "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31");
        execute("""
                INSERT INTO promotion (id, name, scope_type, discount_type, amount, starts_on, ends_on, replaces_id, created_by)
                VALUES ('%s', 'Better', 'SHOP', 'PERCENTAGE', 15.00, '2026-12-01', '2026-12-31', '%s', 'user:kc-maya')""".formatted(UUID.randomUUID(), first));
        assertThatThrownBy(() -> execute("""
                INSERT INTO promotion (id, name, scope_type, discount_type, amount, starts_on, ends_on, replaces_id, created_by)
                VALUES ('%s', 'Ghost', 'SHOP', 'PERCENTAGE', 15.00, '2026-12-01', '2026-12-31', '%s', 'user:kc-maya')""".formatted(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(SQLException.class);
    }

    private static UUID insert(String scope, UUID target, String type, String amount, String from, String to) throws Exception {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO promotion (id, name, scope_type, scope_id, discount_type, amount, starts_on, ends_on, created_by)
                VALUES ('%s', 'Test', '%s', %s, '%s', %s, '%s', '%s', 'user:kc-maya')""".formatted(id, scope, target == null ? "NULL" : "'" + target + "'", type, amount, from, to));
        return id;
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static void migrateTo(MigrationVersion version) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(version).load().migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String stringOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
