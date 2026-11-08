package com.oriah.order.model;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V4: the order's own total becomes authoritative (MVP_PLAN Phase 0, item 4.1).
 *
 * <p>{@code orders} had no shipping or discount column, and
 * {@code Order.recomputeTotals()} ended {@code total = subtotal}, so the order
 * disagreed with what checkout charged. V4 adds the columns -- NULLABLE, with
 * no backfill: NULL means "unknown" on an older order, never a claim of zero
 * -- and a marker that, once set, makes the database itself enforce
 * {@code total = subtotal + shipping - discount}.
 *
 * <p>Each test migrates its own fresh schema on a real Postgres, so one test
 * can never pass on a schema another test already migrated.
 */
class OrderTotalsMigrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private String schema;

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void freshSchema() {
        schema = "v4_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + schema);
        }
        return c;
    }

    /** An order row as V4 sees it; null arguments are written as SQL NULL. */
    private UUID insertMarked(boolean marked, String subtotal, String shipping, String discount,
                              String total, String option) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); var ps = c.prepareStatement(
                "INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency, shipping_amount, "
                        + "discount_amount, delivery_option, totals_authoritative) "
                        + "VALUES (?, 'kc-alice', 'CREATED', ?, ?, 'ZMW', ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setBigDecimal(2, new BigDecimal(subtotal));
            ps.setBigDecimal(3, new BigDecimal(total));
            ps.setBigDecimal(4, shipping == null ? null : new BigDecimal(shipping));
            ps.setBigDecimal(5, discount == null ? null : new BigDecimal(discount));
            ps.setString(6, option);
            ps.setBoolean(7, marked);
            ps.executeUpdate();
        }
        return id;
    }

    @Test
    @DisplayName("an order placed before V4 migrates with NULLs -- unknown, never a claim of zero -- and unmarked")
    void aLegacyRowMigratesWithNullsAndUnmarked() throws SQLException {
        flyway("3").migrate();
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency, created_at) "
                    + "VALUES ('" + id + "', 'kc-alice', 'PAID', 250.00, 250.00, 'ZMW', now())");
        }

        flyway(null).migrate();

        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT shipping_amount, discount_amount, delivery_option, "
                     + "totals_authoritative FROM orders WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("shipping_amount")).isNull();
            assertThat(rs.getBigDecimal("discount_amount")).isNull();
            assertThat(rs.getString("delivery_option")).isNull();
            assertThat(rs.getBoolean("totals_authoritative")).isFalse();
        }
    }

    @Test
    @DisplayName("a marked order breaking total = subtotal + shipping - discount is refused by the database")
    void aMarkedOrderBreakingTheFormulaIsRefused() {
        flyway(null).migrate();

        assertThatThrownBy(() -> insertMarked(true, "250.00", "0.00", "0.00", "1.00", "SELF_COLLECT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_totals_authoritative_chk");
    }

    @Test
    @DisplayName("a marked order with unknown shipping is refused")
    void aMarkedOrderWithUnknownShippingIsRefused() {
        flyway(null).migrate();

        assertThatThrownBy(() -> insertMarked(true, "250.00", null, "0.00", "250.00", "SELF_COLLECT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_totals_authoritative_chk");
    }

    @Test
    @DisplayName("a marked order without a delivery option is refused")
    void aMarkedOrderWithoutADeliveryOptionIsRefused() {
        flyway(null).migrate();

        assertThatThrownBy(() -> insertMarked(true, "250.00", "0.00", "0.00", "250.00", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_totals_authoritative_chk");
    }

    @Test
    @DisplayName("a marked order obeying the formula is stored: 250 + 125 - 10 = 365")
    void aMarkedOrderObeyingTheFormulaIsStored() throws SQLException {
        flyway(null).migrate();

        UUID id = insertMarked(true, "250.00", "125.00", "10.00", "365.00", "OWN_DELIVERY");

        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT total FROM orders WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("total")).isEqualByComparingTo("365.00");
        }
    }

    @Test
    @DisplayName("an unmarked order is not held to the formula -- older orders stay as they were")
    void anUnmarkedOrderIsNotHeldToTheFormula() throws SQLException {
        flyway(null).migrate();

        assertThat(insertMarked(false, "250.00", null, null, "1.00", null)).isNotNull();
    }

    @Test
    @DisplayName("a negative shipping or discount amount is refused")
    void aNegativeAmountIsRefused() {
        flyway(null).migrate();

        assertThatThrownBy(() -> insertMarked(false, "250.00", "-1.00", "0.00", "249.00", "SELF_COLLECT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_shipping_amount_check");
        assertThatThrownBy(() -> insertMarked(false, "250.00", "0.00", "-1.00", "251.00", "SELF_COLLECT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_discount_amount_check");
    }

    @Test
    @DisplayName("a delivery option that is not an option code is refused")
    void aMalformedDeliveryOptionIsRefused() {
        flyway(null).migrate();

        assertThatThrownBy(() -> insertMarked(false, "250.00", "0.00", "0.00", "250.00", "self collect"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_delivery_option_format_chk");
    }
}
