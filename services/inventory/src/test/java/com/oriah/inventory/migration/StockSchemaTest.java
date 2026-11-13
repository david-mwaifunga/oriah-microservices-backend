package com.oriah.inventory.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database keeps its own promises about cost lots and the stock ledger, whatever the application does:
 * a lot's cost and identity never change, a lot that was replaced holds nothing, the ledger is append-only,
 * every row has a reason from the closed list, and a row is reversed by a re-cut at most once. Raw SQL
 * against the real migrations, no Spring context.
 */
@Testcontainers
class StockSchemaTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
    }

    @BeforeEach
    void wipe() throws SQLException {
        exec("TRUNCATE stock_movement, stock_action, cost_lot, inventory");
    }

    // ----- helpers -----

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** The message of the error the database raises for this statement, or null if it ran. */
    private static String refusal(String sql) {
        try {
            exec(sql);
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static UUID variant(String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO inventory (variant_id, on_hand, reserved, kind) VALUES ('%s', 0, 0, '%s')".formatted(id, kind));
        return id;
    }

    private static final String LOT_COLUMNS = """
            (id, variant_id, kind, sku, product_title, variant_title, consignment_id, consignment_reference,
             consignment_line_id, supplier_id, supplier_name, received_at, fifo_tiebreak, slice_position, revision,
             qty_received, qty_remaining, unit_cost, is_estimated)""";

    private static UUID lot(UUID variant, UUID line, int revision, int slice, int received, int remaining, String cost) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("""
                INSERT INTO cost_lot %s VALUES ('%s', '%s', 'SELLABLE', 'RING-1', 'Ring', 'Ring - M', '%s', 'C-000001',
                        '%s', NULL, NULL, '2026-11-12T10:00:00Z', 1, %d, %d, %d, %d, %s, false)"""
                .formatted(LOT_COLUMNS, id, variant, UUID.randomUUID(), line, slice, revision, received, remaining, cost));
        return id;
    }

    private static UUID action(UUID variant, String kind, String reason, int qty, String reverses) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("""
                INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor, reverses_action_id)
                VALUES ('%s', '%s', '%s', '%s', %d, '2026-11-12T10:00:00Z', 'user:kc-maya', %s)"""
                .formatted(id, variant, kind, reason, qty, reverses == null ? "NULL" : "'" + reverses + "'"));
        return id;
    }

    private static String movementSql(UUID variant, UUID action, UUID lot, String reason, String role, int delta,
                                      String reverses, String replaces) {
        return """
                INSERT INTO stock_movement (variant_id, action_id, lot_id, reason, role, qty_delta, unit_cost, is_estimated,
                                            kind, reverses_movement_id, replaces_movement_id, occurred_at, actor)
                VALUES ('%s', '%s', '%s', '%s', '%s', %d, 1528.99, false, 'SELLABLE', %s, %s, '2026-11-12T10:00:00Z', 'user:kc-maya')"""
                .formatted(variant, action, lot, reason, role, delta, reverses == null ? "NULL" : reverses,
                        replaces == null ? "NULL" : replaces);
    }

    // ----- cost_lot -----

    @Test
    @DisplayName("a lot's cost, size and identity can never be changed; only what is left, and being replaced")
    void aLotsCostIsFixed() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "1528.99");

        assertThat(refusal("UPDATE cost_lot SET unit_cost = 1.00 WHERE id = '" + l + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET qty_received = 9 WHERE id = '" + l + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET received_at = now() WHERE id = '" + l + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET is_estimated = true WHERE id = '" + l + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET sku = 'OTHER' WHERE id = '" + l + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET qty_remaining = 3 WHERE id = '" + l + "'")).isNull();
        assertThat(refusal("DELETE FROM cost_lot WHERE id = '" + l + "'")).contains("cost_lot");
    }

    @Test
    @DisplayName("what is left is never negative and never more than was received")
    void remainingStaysInBounds() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "10.00");

        assertThat(refusal("UPDATE cost_lot SET qty_remaining = -1 WHERE id = '" + l + "'")).contains("ck_cost_lot_remaining");
        assertThat(refusal("UPDATE cost_lot SET qty_remaining = 5 WHERE id = '" + l + "'")).contains("ck_cost_lot_remaining");
    }

    @Test
    @DisplayName("a replaced lot holds nothing, is replaced in one step, and stays replaced")
    void aReplacedLotHoldsNothing() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "10.00");

        assertThat(refusal("UPDATE cost_lot SET superseded_at = now() WHERE id = '" + l + "'")).contains("ck_cost_lot_superseded");
        assertThat(refusal("UPDATE cost_lot SET superseded_at = now(), qty_remaining = 0 WHERE id = '" + l + "'")).isNull();
        assertThat(refusal("UPDATE cost_lot SET superseded_at = NULL WHERE id = '" + l + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET qty_remaining = 1 WHERE id = '" + l + "'")).contains("ck_cost_lot_superseded");
    }

    @Test
    @DisplayName("a lot has a cost of zero or more, in kwacha, and a line has each slice of each revision once")
    void lotShape() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID line = UUID.randomUUID();
        lot(v, line, 1, 1, 4, 4, "0.00");

        assertThat(refusal("""
                INSERT INTO cost_lot %s VALUES ('%s', '%s', 'SELLABLE', 'R', NULL, 'R', '%s', 'C-1', '%s', NULL, NULL,
                        now(), 1, 1, 1, 1, 1, -0.01, false)""".formatted(LOT_COLUMNS, UUID.randomUUID(), v, UUID.randomUUID(), line)))
                .contains("cost_lot");
        assertThat(refusal("""
                INSERT INTO cost_lot %s VALUES ('%s', '%s', 'SELLABLE', 'R', NULL, 'R', '%s', 'C-1', '%s', NULL, NULL,
                        now(), 1, 1, 1, 1, 1, 5.00, false)""".formatted(LOT_COLUMNS, UUID.randomUUID(), v, UUID.randomUUID(), line)))
                .as("the same slice of the same revision of the same line twice").contains("uq_cost_lot_slice");
        assertThat(refusal("""
                INSERT INTO cost_lot %s VALUES ('%s', '%s', 'JEWEL', 'R', NULL, 'R', '%s', 'C-1', '%s', NULL, NULL,
                        now(), 1, 1, 2, 1, 1, 5.00, false)""".formatted(LOT_COLUMNS, UUID.randomUUID(), v, UUID.randomUUID(), line)))
                .as("only goods and packaging").contains("cost_lot");
    }

    @Test
    @DisplayName("a lot belongs to a stock row, and a stock row is goods or packaging")
    void lotsNeedAStockRow() throws SQLException {
        assertThat(refusal("""
                INSERT INTO cost_lot %s VALUES ('%s', '%s', 'SELLABLE', 'R', NULL, 'R', '%s', 'C-1', '%s', NULL, NULL,
                        now(), 1, 1, 1, 1, 1, 5.00, false)""".formatted(LOT_COLUMNS, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID()))).contains("fk_cost_lot_variant");
        assertThat(refusal("INSERT INTO inventory (variant_id, on_hand, reserved, kind) VALUES ('" + UUID.randomUUID()
                + "', 0, 0, 'SERVICE')")).contains("ck_inventory_kind");
    }

    // ----- stock_action and stock_movement -----

    @Test
    @DisplayName("the ledger is append-only: rows can be added and never changed or removed")
    void theLedgerIsAppendOnly() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "1528.99");
        UUID a = action(v, "SALE", "SALE", 1, null);
        assertThat(refusal(movementSql(v, a, l, "SALE", "ORIGINAL", -1, null, null))).isNull();

        assertThat(refusal("UPDATE stock_movement SET qty_delta = -2")).contains("append-only");
        assertThat(refusal("DELETE FROM stock_movement")).contains("append-only");
        assertThat(refusal("UPDATE stock_action SET qty = 2")).contains("append-only");
        assertThat(refusal("DELETE FROM stock_action")).contains("append-only");
    }

    @Test
    @DisplayName("every action and movement carries a reason from the closed list, and a plain kwacha cost")
    void theClosedList() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "1528.99");

        assertThat(refusal("""
                INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor)
                VALUES ('%s', '%s', 'SALE', 'STOLEN_BY_ELVES', 1, now(), 'user:x')""".formatted(UUID.randomUUID(), v)))
                .contains("ck_stock_action_reason");
        UUID a = action(v, "SALE", "SALE", 1, null);
        assertThat(refusal(movementSql(v, a, l, "STOLEN_BY_ELVES", "ORIGINAL", -1, null, null))).contains("ck_stock_movement_reason");
        for (String reason : new String[] {"CONSIGNMENT_RECEIVED", "SALE", "RETURN", "WRITE_OFF_LOST", "WRITE_OFF_STOLEN",
                "WRITE_OFF_BROKEN", "GIVEN_AWAY_MODEL", "GIVEN_AWAY_INFLUENCER", "GIVEN_AWAY_GIFT", "GIVEN_AWAY_PRIZE",
                "TAKEN_BACK", "CANCELLED_BEFORE_DISPATCH", "RELISTED_MARKED_DOWN", "BOX_SWAPPED"}) {
            assertThat(refusal("""
                    INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor)
                    VALUES ('%s', '%s', 'SALE', '%s', 1, now(), 'user:x')""".formatted(UUID.randomUUID(), v, reason)))
                    .as(reason).isNull();
        }
    }

    @Test
    @DisplayName("a movement belongs to an action of the SAME variant, and to a lot that exists")
    void movementsBelongToTheirAction() throws SQLException {
        UUID v1 = variant("SELLABLE");
        UUID v2 = variant("SELLABLE");
        UUID l1 = lot(v1, UUID.randomUUID(), 1, 1, 4, 4, "1528.99");
        UUID a2 = action(v2, "SALE", "SALE", 1, null);

        assertThat(refusal(movementSql(v1, a2, l1, "SALE", "ORIGINAL", -1, null, null))).contains("fk_stock_movement_action");
        assertThat(refusal(movementSql(v1, action(v1, "SALE", "SALE", 1, null), UUID.randomUUID(), "SALE", "ORIGINAL", -1, null, null)))
                .contains("fk_stock_movement_lot");
    }

    @Test
    @DisplayName("an action is for a positive quantity, is recorded in order, and a take-back names what it takes back")
    void actions() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID gift = action(v, "GIVE_AWAY", "GIVEN_AWAY_GIFT", 3, null);

        assertThat(refusal("INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor) VALUES ('"
                + UUID.randomUUID() + "', '" + v + "', 'SALE', 'SALE', 0, now(), 'u')")).contains("ck_stock_action_qty");
        assertThat(refusal("INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor) VALUES ('"
                + UUID.randomUUID() + "', '" + v + "', 'TAKE_BACK', 'TAKEN_BACK', 1, now(), 'u')")).contains("ck_stock_action_take_back");
        assertThat(refusal("INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor, reverses_action_id) VALUES ('"
                + UUID.randomUUID() + "', '" + v + "', 'SALE', 'SALE', 1, now(), 'u', '" + gift + "')")).contains("ck_stock_action_take_back");
        assertThat(refusal("INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor, reverses_action_id) VALUES ('"
                + UUID.randomUUID() + "', '" + v + "', 'TAKE_BACK', 'TAKEN_BACK', 1, now(), 'u', '" + gift + "')")).isNull();
        assertThat(refusal("INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor, reverses_action_id) VALUES ('"
                + UUID.randomUUID() + "', '" + variant("SELLABLE") + "', 'TAKE_BACK', 'TAKEN_BACK', 1, now(), 'u', '" + gift + "')"))
                .as("it must be an action of the same variant").contains("fk_stock_action_reverses");
        assertThat(refusal("INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor) VALUES ('" + gift
                + "', '" + v + "', 'SALE', 'SALE', 1, now(), 'u')")).as("an action id is used once, for one variant").contains("stock_action_pkey");
        assertThat(one("SELECT count(*) FROM stock_action WHERE seq IS NOT NULL")).isEqualTo("2");
    }

    @Test
    @DisplayName("a re-cut's rows say what they are: a reversal points at what it reverses, a lot transfer is a correction and nothing else")
    void rolesAndPointers() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "1528.99");
        UUID a = action(v, "SALE", "SALE", 1, null);

        assertThat(refusal(movementSql(v, a, l, "SALE", "RECUT_REVERSAL", 1, null, null))).contains("ck_stock_movement_pointers");
        assertThat(refusal(movementSql(v, a, l, "SALE", "RECUT_REPLACEMENT", -1, null, null))).contains("ck_stock_movement_pointers");
        assertThat(refusal(movementSql(v, a, l, "SALE", "LOT_OPEN", 4, null, null))).as("a lot transfer is not a sale").contains("ck_stock_movement_pointers");
        assertThat(refusal(movementSql(v, a, l, "COST_CORRECTED", "ORIGINAL", -1, null, null))).as("COST_CORRECTED is only for lot transfers")
                .contains("ck_stock_movement_pointers");
        assertThat(refusal(movementSql(v, a, l, "COST_CORRECTED", "LOT_OPEN", 4, null, null))).isNull();
        assertThat(refusal(movementSql(v, a, l, "COST_CORRECTED", "LOT_CLOSE", -4, null, null))).isNull();
        assertThat(refusal(movementSql(v, a, l, "SALE", "ORIGINAL", 0, null, null))).contains("ck_stock_movement_delta");
    }

    @Test
    @DisplayName("a row can be reversed by a re-cut only once; and 'live' is whatever has not been reversed")
    void liveRows() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "1528.99");
        UUID a = action(v, "SALE", "SALE", 2, null);
        exec(movementSql(v, a, l, "SALE", "ORIGINAL", -2, null, null));
        String original = one("SELECT id FROM stock_movement ORDER BY id LIMIT 1");
        assertThat(one("SELECT count(*) FROM live_movement")).isEqualTo("1");

        exec(movementSql(v, a, l, "SALE", "RECUT_REVERSAL", 2, original, null));
        assertThat(refusal(movementSql(v, a, l, "SALE", "RECUT_REVERSAL", 2, original, null)))
                .as("the same row reversed a second time").contains("uq_stock_movement_reversed_once");
        assertThat(one("SELECT count(*) FROM live_movement")).as("the original is no longer live; a reversal never is").isEqualTo("0");

        exec(movementSql(v, a, l, "SALE", "RECUT_REPLACEMENT", -2, null, original));
        assertThat(one("SELECT count(*) FROM live_movement")).as("the replacement is").isEqualTo("1");
        assertThat(one("SELECT role FROM live_movement")).isEqualTo("RECUT_REPLACEMENT");
    }

    @Test
    @DisplayName("every movement says how its cost was worked out: FIFO, by when the lot was received")
    void methodAndBasis() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID l = lot(v, UUID.randomUUID(), 1, 1, 4, 4, "1528.99");
        UUID a = action(v, "SALE", "SALE", 1, null);
        exec(movementSql(v, a, l, "SALE", "ORIGINAL", -1, null, null));

        assertThat(one("SELECT cost_method || '/' || cost_basis FROM stock_movement")).isEqualTo("FIFO/RECEIVED_AT");
        assertThat(refusal("""
                INSERT INTO stock_movement (variant_id, action_id, lot_id, reason, role, qty_delta, unit_cost, is_estimated, kind,
                                            cost_method, occurred_at, actor)
                VALUES ('%s', '%s', '%s', 'SALE', 'ORIGINAL', -1, 1.00, false, 'SELLABLE', 'LIFO', now(), 'u')""".formatted(v, a, l)))
                .contains("ck_stock_movement_method");
    }

    // ----- the catch-up bookkeeping -----

    @Test
    @DisplayName("outbox_catch_up exists, as the event log module's reference defines it")
    void catchUpTable() throws SQLException {
        assertThat(one("SELECT count(*) FROM information_schema.columns WHERE table_name = 'outbox_catch_up'")).isEqualTo("5");
    }
}
