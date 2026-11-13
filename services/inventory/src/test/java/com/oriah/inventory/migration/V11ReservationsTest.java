package com.oriah.inventory.migration;

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
 * V11 gives a reservation an owner and a clock (Phase 7): a header per order with a line per piece and per box, so a hold can be released
 * exactly once; a box's set-aside record learns how much of it is held by reservations at this moment ({@code held}); a box swap after
 * payment is kept as a fact of its own; and inventory becomes an event producer (the outbox).
 *
 * <p>Driven through Flyway directly, because the rows that matter are the ones that exist BEFORE the migration: a stock row's old
 * {@code reserved} was a bare counter with no owner and no time, so nothing could ever say whose it was or let it go. V11 clears them
 * (developer data only: nothing goes live before every phase is built), exactly as V7 removed stock that had no cost.
 */
@Testcontainers
class V11ReservationsTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static final UUID RING = UUID.fromString("a1111111-1111-4111-8111-111111111111");
    private static final UUID BOX = UUID.fromString("b2222222-2222-4222-8222-222222222222");
    private static final UUID PRODUCT = UUID.fromString("c3333333-3333-4333-8333-333333333333");

    @BeforeAll
    static void migrateWithStockFromBeforeV11() throws Exception {
        migrateTo(MigrationVersion.fromVersion("10"));
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO inventory (variant_id, on_hand, reserved, kind) VALUES ('%s', 7, 2, 'SELLABLE'), ('%s', 200, 5, 'PACKAGING')"
                    .formatted(RING, BOX));
            s.executeUpdate("UPDATE inventory SET committed = 10 WHERE variant_id = '" + BOX + "'");
            s.executeUpdate("""
                    INSERT INTO box_commitment (id, product_id, box_variant_id, quantity, unpublish_count, committed_at, committed_by)
                    VALUES ('%s', '%s', '%s', 10, 0, now(), 'user:kc-maya')""".formatted(UUID.randomUUID(), PRODUCT, BOX));
        }
        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("a counter with no owner cannot be released by anyone, so V11 clears it; what is set aside and what is held are untouched")
    void ownerlessReservedCountersAreCleared() throws Exception {
        assertThat(stringOf("SELECT reserved::text FROM inventory WHERE variant_id = '" + RING + "'")).isEqualTo("0");
        assertThat(stringOf("SELECT reserved::text FROM inventory WHERE variant_id = '" + BOX + "'")).isEqualTo("0");
        assertThat(stringOf("SELECT on_hand::text || '/' || committed::text FROM inventory WHERE variant_id = '" + BOX + "'")).isEqualTo("200/10");
        assertThat(stringOf("SELECT quantity::text || '/' || held::text FROM box_commitment WHERE product_id = '" + PRODUCT + "'")).isEqualTo("10/0");
    }

    @Test
    @DisplayName("a commitment's held boxes are never below zero and never above what is promised")
    void heldStaysWithinThePromise() {
        assertThatThrownBy(() -> execute("UPDATE box_commitment SET held = 11 WHERE product_id = '" + PRODUCT + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("box_commitment_held_within_quantity");
        assertThatThrownBy(() -> execute("UPDATE box_commitment SET held = -1 WHERE product_id = '" + PRODUCT + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("box_commitment_held_within_quantity");
    }

    @Test
    @DisplayName("an order holds at most one reservation at a time, but may have ended ones beside it; a status it does not know is refused")
    void oneHeldReservationPerOrder() throws Exception {
        UUID order = UUID.randomUUID();
        insertReservation(order, "HELD");

        assertThatThrownBy(() -> insertReservation(order, "HELD")).isInstanceOf(SQLException.class).hasMessageContaining("reservation_one_held_per_order");
        insertReservation(order, "EXPIRED");
        insertReservation(order, "RELEASED");
        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), "WISHED")).isInstanceOf(SQLException.class).hasMessageContaining("reservation_status_known");
    }

    @Test
    @DisplayName("a reservation is held until it ends: HELD has no end time, every other status has one, and it always expires after it was made")
    void endedExactlyWhenNotHeld() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO reservation (id, order_id, status, held_at, expires_at, created_by)
                VALUES ('%s', '%s', 'HELD', now(), now() - interval '1 minute', 'user:test')""".formatted(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(SQLException.class).hasMessageContaining("reservation_expiry_after_hold");
        assertThatThrownBy(() -> execute("""
                INSERT INTO reservation (id, order_id, status, held_at, expires_at, created_by)
                VALUES ('%s', '%s', 'CONSUMED', now(), now() + interval '1 minute', 'user:test')""".formatted(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(SQLException.class).hasMessageContaining("reservation_ended_when_not_held");
    }

    @Test
    @DisplayName("a line says where its units came from: from the product's set-aside or from free, adding up; a box line names its product")
    void aLineAddsUp() throws Exception {
        UUID reservation = UUID.randomUUID();
        execute("""
                INSERT INTO reservation (id, order_id, status, held_at, expires_at, created_by)
                VALUES ('%s', '%s', 'HELD', now(), now() + interval '10 minutes', 'user:test')""".formatted(reservation, UUID.randomUUID()));
        UUID commitment = UUID.randomUUID();

        insertLine(reservation, "BOX", PRODUCT, 3, 2, 1, commitment);        // fine: 2 + 1 = 3
        assertThatThrownBy(() -> insertLine(reservation, "BOX", PRODUCT, 3, 2, 2, commitment))
                .isInstanceOf(SQLException.class).hasMessageContaining("reservation_line_split_adds_up");
        assertThatThrownBy(() -> insertLine(reservation, "BOX", null, 3, 0, 3, null))
                .isInstanceOf(SQLException.class).hasMessageContaining("reservation_line_box_names_product");
        assertThatThrownBy(() -> insertLine(reservation, "GOODS", null, 3, 1, 2, commitment))
                .isInstanceOf(SQLException.class).hasMessageContaining("reservation_line_goods_not_committed");
        assertThatThrownBy(() -> insertLine(reservation, "BOX", PRODUCT, 3, 0, 3, commitment))
                .isInstanceOf(SQLException.class).hasMessageContaining("reservation_line_commitment_only_with_committed");
        assertThatThrownBy(() -> insertLine(reservation, "GOODS", null, 0, 0, 0, null))
                .isInstanceOf(SQLException.class).hasMessageContaining("reservation_line_quantity_positive");
    }

    @Test
    @DisplayName("a sale line appears once in a reservation, but the same sale line may be held again by a LATER reservation of the same order")
    void aSaleLineOncePerReservation() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        execute("INSERT INTO reservation (id, order_id, status, held_at, expires_at, ended_at, created_by) VALUES ('%s', '%s', 'EXPIRED', now() - interval '1 hour', now() - interval '50 minutes', now() - interval '50 minutes', 'user:test')"
                .formatted(first, order));
        execute("INSERT INTO reservation (id, order_id, status, held_at, expires_at, created_by) VALUES ('%s', '%s', 'HELD', now(), now() + interval '10 minutes', 'user:test')"
                .formatted(second, order));
        UUID saleLine = UUID.randomUUID();

        insertLineWithSaleLine(first, saleLine);
        insertLineWithSaleLine(second, saleLine);
        assertThatThrownBy(() -> insertLineWithSaleLine(second, saleLine)).isInstanceOf(SQLException.class).hasMessageContaining("reservation_line_reservation_id_sale_line_id_key");
    }

    @Test
    @DisplayName("a box swap is a fact kept for ever: update, delete and truncate are refused, and a swap names two different actions")
    void boxSwapIsAppendOnly() throws Exception {
        UUID amendment = UUID.randomUUID();
        execute("""
                INSERT INTO box_swap (amendment_id, order_id, product_id, original_action_id, replacement_action_id, quantity, reason, occurred_at, actor)
                VALUES ('%s', '%s', '%s', '%s', '%s', 1, 'customer asked for the velvet box', now(), 'user:kc-maya')"""
                .formatted(amendment, UUID.randomUUID(), PRODUCT, UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> execute("UPDATE box_swap SET quantity = 2")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM box_swap")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("TRUNCATE box_swap")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        UUID same = UUID.randomUUID();
        assertThatThrownBy(() -> execute("""
                INSERT INTO box_swap (amendment_id, order_id, product_id, original_action_id, replacement_action_id, quantity, reason, occurred_at, actor)
                VALUES ('%s', '%s', '%s', '%s', '%s', 1, 'same box', now(), 'user:kc-maya')""".formatted(UUID.randomUUID(), UUID.randomUUID(), PRODUCT, same, same)))
                .isInstanceOf(SQLException.class).hasMessageContaining("box_swap_different_actions");
        assertThatThrownBy(() -> execute("""
                INSERT INTO box_swap (amendment_id, order_id, product_id, original_action_id, replacement_action_id, quantity, reason, occurred_at, actor)
                VALUES ('%s', '%s', '%s', '%s', '%s', 1, 'twice', now(), 'user:kc-maya')""".formatted(amendment, UUID.randomUUID(), PRODUCT, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(SQLException.class).hasMessageContaining("box_swap_pkey");
    }

    @Test
    @DisplayName("inventory has an outbox to publish from, append-only, and the relay's cursor starts at zero")
    void inventoryHasAnOutbox() throws Exception {
        assertThat(stringOf("SELECT last_outbox_id::text FROM outbox_relay_cursor WHERE relay = 'main'")).isEqualTo("0");
        execute("""
                INSERT INTO outbox (event_id, aggregate_type, aggregate_id, seq, event_type, schema_version, topic, occurred_at, actor, payload)
                VALUES ('%s', 'reservation', '%s', 1, 'reservation.expired', 1, 'oriah.inventory', now(), 'system:inventory', '{}')"""
                .formatted(UUID.randomUUID(), UUID.randomUUID()));
        assertThatThrownBy(() -> execute("UPDATE outbox SET actor = 'x'")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("TRUNCATE outbox")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
    }

    private static void insertReservation(UUID order, String status) throws Exception {
        String ended = "HELD".equals(status) ? "NULL" : "now()";
        execute("""
                INSERT INTO reservation (id, order_id, status, held_at, expires_at, ended_at, created_by)
                VALUES ('%s', '%s', '%s', now() - interval '1 hour', now() + interval '10 minutes', %s, 'user:test')"""
                .formatted(UUID.randomUUID(), order, status, ended));
    }

    private static void insertLine(UUID reservation, String kind, UUID product, int qty, int fromCommitted, int fromFree, UUID commitment) throws Exception {
        execute("""
                INSERT INTO reservation_line (id, reservation_id, sale_line_id, variant_id, kind, product_id, quantity, from_committed, from_free, commitment_id)
                VALUES ('%s', '%s', '%s', '%s', '%s', %s, %d, %d, %d, %s)""".formatted(UUID.randomUUID(), reservation, UUID.randomUUID(),
                "BOX".equals(kind) ? BOX : RING, kind, product == null ? "NULL" : "'" + product + "'", qty, fromCommitted, fromFree,
                commitment == null ? "NULL" : "'" + commitment + "'"));
    }

    private static void insertLineWithSaleLine(UUID reservation, UUID saleLine) throws Exception {
        execute("""
                INSERT INTO reservation_line (id, reservation_id, sale_line_id, variant_id, kind, quantity, from_committed, from_free)
                VALUES ('%s', '%s', '%s', '%s', 'GOODS', 1, 0, 1)""".formatted(UUID.randomUUID(), reservation, saleLine, RING));
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
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String stringOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
