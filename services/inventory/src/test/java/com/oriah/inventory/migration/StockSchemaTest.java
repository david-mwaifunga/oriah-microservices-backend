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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        now(), 1, 2, 1, 1, 1, -0.01, false)""".formatted(LOT_COLUMNS, UUID.randomUUID(), v, UUID.randomUUID(), line)))
                // Slice 2, which the line does not have yet: only the cost can be what is refused (the same slice again would
                // be refused by the uniqueness rule first, and a message that merely names the table proves nothing).
                .contains("ck_cost_lot_unit_cost");
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
                "TAKEN_BACK", "CANCELLED_BEFORE_DISPATCH", "RELISTED_MARKED_DOWN", "BOX_SWAPPED", "WRITE_OFF_RETURNED"}) {
            // On a movement row, where a reason has no shape of its own. (Since V13 an ACTION carries some reasons only in the shape
            // they have: a return is a take-back of one piece, never a "sale". kindsAndReasonsOfTheNewActions pins those.)
            assertThat(refusal(movementSql(v, a, l, reason, "ORIGINAL", -1, null, null))).as(reason).isNull();
        }
        for (String reason : new String[] {"SALE", "BOX_SWAPPED", "TAKEN_BACK", "CANCELLED_BEFORE_DISPATCH", "WRITE_OFF_LOST", "GIVEN_AWAY_GIFT"}) {
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

    // ----- returned pieces, their outcomes and marked-down lots (Phase 12, V13) -----

    private static String actionSql(UUID id, UUID variant, String kind, String reason, int qty, UUID reverses, UUID assesses) {
        return """
                INSERT INTO stock_action (id, variant_id, kind, reason, qty, occurred_at, actor, reverses_action_id, assesses_action_id)
                VALUES ('%s', '%s', '%s', '%s', %d, '2026-11-12T10:00:00Z', 'user:kc-maya', %s, %s)"""
                .formatted(id, variant, kind, reason, qty, reverses == null ? "NULL" : "'" + reverses + "'",
                        assesses == null ? "NULL" : "'" + assesses + "'");
    }

    /** A sale of {@code qty} and one piece of it returned: the returned piece's action id. */
    private static UUID returnedPiece(UUID variant, UUID sale) throws SQLException {
        UUID piece = UUID.randomUUID();
        exec(actionSql(piece, variant, "TAKE_BACK", "RETURN", 1, sale, null));
        return piece;
    }

    @Test
    @DisplayName("the three new kinds of action and the new reason are accepted, each only in the shape it has")
    void kindsAndReasonsOfTheNewActions() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID child = variant("SELLABLE");
        UUID sale = action(v, "SALE", "SALE", 4, null);
        UUID fine = returnedPiece(v, sale), broken = returnedPiece(v, sale), marked = returnedPiece(v, sale);

        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RELEASE", "RETURN", 1, null, fine))).isNull();
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RETURN_EXIT", "WRITE_OFF_RETURNED", 1, null, broken))).isNull();
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RETURN_EXIT", "RELISTED_MARKED_DOWN", 1, null, marked))).isNull();
        assertThat(refusal(actionSql(UUID.randomUUID(), child, "RELIST_IN", "RELISTED_MARKED_DOWN", 1, null, null))).isNull();

        UUID another = returnedPiece(v, sale);
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RELEASE", "SALE", 1, null, another))).as("a release is of a return").contains("ck_stock_action_return_steps");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RETURN_EXIT", "WRITE_OFF_BROKEN", 1, null, another))).contains("ck_stock_action_return_steps");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "WRITE_OFF", "WRITE_OFF_RETURNED", 1, null, null)))
                .as("the ordinary write-off never carries the returned reason").contains("ck_stock_action_return_steps");
        assertThat(refusal(actionSql(UUID.randomUUID(), child, "RELIST_IN", "SALE", 1, null, null))).contains("ck_stock_action_return_steps");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "SALE", "RELISTED_MARKED_DOWN", 1, null, null))).contains("ck_stock_action_return_steps");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RELEASE", "RETURN", 1, null, null))).as("an outcome names its piece").contains("ck_stock_action_assesses");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "SALE", "SALE", 1, null, another))).as("only an outcome does").contains("ck_stock_action_assesses");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RESTOCK", "SALE", 1, null, null))).contains("ck_stock_action_kind");
    }

    @Test
    @DisplayName("every kind and reason that existed before is still accepted")
    void theOldKindsStillInsert() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID sale = action(v, "SALE", "SALE", 2, null);
        UUID writeOff = action(v, "WRITE_OFF", "WRITE_OFF_LOST", 1, null);
        action(v, "GIVE_AWAY", "GIVEN_AWAY_GIFT", 1, null);
        action(v, "TAKE_BACK", "TAKEN_BACK", 1, writeOff.toString());
        action(v, "TAKE_BACK", "BOX_SWAPPED", 2, sale.toString());
        action(v, "RECEIPT", "CONSIGNMENT_RECEIVED", 5, null);
        action(v, "CORRECTION", "COST_CORRECTED", 5, null);
        action(v, "TAKE_BACK", "CANCELLED_BEFORE_DISPATCH", 1, action(v, "SALE", "SALE", 1, null).toString());

        assertThat(one("SELECT count(*) FROM stock_action WHERE variant_id = '" + v + "'")).isEqualTo("9");
    }

    @Test
    @DisplayName("a returned piece is one piece: it comes back, is released or leaves one at a time")
    void aReturnIsOnePiece() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID sale = action(v, "SALE", "SALE", 4, null);
        UUID piece = returnedPiece(v, sale);

        assertThat(refusal(actionSql(UUID.randomUUID(), v, "TAKE_BACK", "RETURN", 2, sale, null))).contains("ck_stock_action_return_steps");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RETURN_EXIT", "WRITE_OFF_RETURNED", 2, null, piece))).contains("ck_stock_action_return_steps");
    }

    @Test
    @DisplayName("an outcome decides a returned piece, and a piece is decided once")
    void anOutcomeDecidesOnePieceOnce() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID sale = action(v, "SALE", "SALE", 3, null);
        UUID piece = returnedPiece(v, sale);
        UUID writeOff = action(v, "WRITE_OFF", "WRITE_OFF_LOST", 1, null);
        UUID ordinaryTakeBack = action(v, "TAKE_BACK", "TAKEN_BACK", 1, writeOff.toString());

        exec(actionSql(UUID.randomUUID(), v, "RELEASE", "RETURN", 1, null, piece));
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RETURN_EXIT", "WRITE_OFF_RETURNED", 1, null, piece)))
                .as("it was already decided").contains("uq_stock_action_assessed_once");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RELEASE", "RETURN", 1, null, ordinaryTakeBack)))
                .as("an ordinary take-back is not a returned piece").contains("an outcome decides a returned piece");
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "RELEASE", "RETURN", 1, null, sale))).contains("an outcome decides a returned piece");
    }

    @Test
    @DisplayName("the database itself never lets more be taken back than an action moved")
    void neverMoreTakenBackThanMoved() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID sale = action(v, "SALE", "SALE", 2, null);

        returnedPiece(v, sale);
        action(v, "TAKE_BACK", "CANCELLED_BEFORE_DISPATCH", 1, sale.toString());
        assertThat(refusal(actionSql(UUID.randomUUID(), v, "TAKE_BACK", "RETURN", 1, sale, null))).contains("already taken back");
    }

    @Test
    @DisplayName("pieces waiting to be looked at are goods, are counted on hand, and never push the row past what it holds")
    void quarantineStaysWithinStock() throws SQLException {
        UUID v = variant("SELLABLE");
        UUID box = variant("PACKAGING");
        exec("UPDATE inventory SET on_hand = 3, reserved = 1, quarantined = 2 WHERE variant_id = '" + v + "'");

        assertThat(refusal("UPDATE inventory SET quarantined = 3 WHERE variant_id = '" + v + "'")).contains("inventory_committed_within_stock");
        assertThat(refusal("UPDATE inventory SET quarantined = -1 WHERE variant_id = '" + v + "'")).contains("inventory_quarantined_nonnegative");
        assertThat(refusal("UPDATE inventory SET on_hand = 5, quarantined = 1 WHERE variant_id = '" + box + "'"))
                .as("a box that went out does not come back").contains("inventory_quarantine_only_sellable");
    }

    private static String childLotSql(UUID id, UUID variant, UUID line, int received, UUID source, UUID sourceAction) {
        return """
                INSERT INTO cost_lot (id, variant_id, kind, sku, product_title, variant_title, consignment_id, consignment_reference,
                        consignment_line_id, supplier_id, supplier_name, received_at, fifo_tiebreak, slice_position, revision,
                        qty_received, qty_remaining, unit_cost, is_estimated, source_lot_id, source_action_id)
                VALUES ('%s', '%s', 'SELLABLE', 'RING-1-R-0000ABCD', 'Ring', 'Ring - M - returned, marked down', '%s', 'C-000001',
                        '%s', NULL, NULL, '2026-11-12T10:00:00Z', 1, 1, 1, %d, %d, 1528.99, false, %s, %s)"""
                .formatted(id, variant, UUID.randomUUID(), line, received, received, source == null ? "NULL" : "'" + source + "'",
                        sourceAction == null ? "NULL" : "'" + sourceAction + "'");
    }

    @Test
    @DisplayName("a marked-down lot is one piece of goods, and says both the lot it came from and the movement that took it out")
    void aChildLotIsOnePieceAndPointsAtItsSource() throws SQLException {
        UUID v = variant("SELLABLE"), child = variant("SELLABLE");
        UUID line = UUID.randomUUID();
        UUID source = lot(v, line, 1, 1, 10, 10, "1528.99");
        UUID exit = UUID.randomUUID();

        assertThat(refusal(childLotSql(UUID.randomUUID(), child, line, 1, source, exit))).isNull();
        assertThat(refusal(childLotSql(UUID.randomUUID(), child, line, 2, source, UUID.randomUUID()))).contains("ck_cost_lot_child_is_one");
        assertThat(refusal(childLotSql(UUID.randomUUID(), child, line, 1, source, null))).contains("ck_cost_lot_child_whole");
        assertThat(refusal(childLotSql(UUID.randomUUID(), child, line, 1, null, UUID.randomUUID()))).contains("ck_cost_lot_child_whole");
        assertThat(refusal(childLotSql(UUID.randomUUID(), child, line, 1, UUID.randomUUID(), UUID.randomUUID()))).as("the source is a lot").contains("cost_lot");
        assertThat(refusal(childLotSql(UUID.randomUUID(), child, line, 1, source, exit))).as("one lot per piece moved, per revision").contains("uq_cost_lot_child");
    }

    @Test
    @DisplayName("a line still has each slice of each revision once among its own lots; a marked-down lot of the same line does not count")
    void rootSlicesStayUnique() throws SQLException {
        UUID v = variant("SELLABLE"), child = variant("SELLABLE");
        UUID line = UUID.randomUUID();
        UUID source = lot(v, line, 1, 1, 10, 10, "1528.99");

        assertThat(refusal(childLotSql(UUID.randomUUID(), child, line, 1, source, UUID.randomUUID()))).as("same line, revision 1, slice 1: a child").isNull();
        assertThatThrownBy(() -> lot(v, line, 1, 1, 4, 4, "1528.99")).hasMessageContaining("uq_cost_lot_slice");
    }

    @Test
    @DisplayName("where a marked-down lot came from can never be changed")
    void aLotsSourceNeverChanges() throws SQLException {
        UUID v = variant("SELLABLE"), child = variant("SELLABLE");
        UUID line = UUID.randomUUID();
        UUID source = lot(v, line, 1, 1, 10, 10, "1528.99");
        UUID other = lot(v, UUID.randomUUID(), 1, 1, 10, 10, "1600.00");
        UUID id = UUID.randomUUID();
        exec(childLotSql(id, child, line, 1, source, UUID.randomUUID()));

        assertThat(refusal("UPDATE cost_lot SET source_lot_id = '" + other + "' WHERE id = '" + id + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET source_action_id = '" + UUID.randomUUID() + "' WHERE id = '" + id + "'")).contains("cost_lot");
        assertThat(refusal("UPDATE cost_lot SET qty_remaining = 0 WHERE id = '" + id + "'")).as("what is left may still change").isNull();
    }

    @Test
    @DisplayName("a reversed sale, a returned piece and a marked-down relist are records: never changed after the fact, never removed")
    void theReturnRecordsAreRecords() throws SQLException {
        UUID reversal = UUID.randomUUID(), piece = UUID.randomUUID(), variant = UUID.randomUUID();
        exec("""
                INSERT INTO sale_reversal (id, kind, order_id, reservation_id, occurred_at, request_digest, recorded_at, actor)
                VALUES ('%s', 'RETURN', '%s', NULL, now(), '%s', now(), 'service:order')""".formatted(reversal, UUID.randomUUID(), "a".repeat(64)));
        exec("""
                INSERT INTO returned_piece (piece_id, reversal_id, sale_line_id, variant_id, restored_at)
                VALUES ('%s', '%s', '%s', '%s', now())""".formatted(piece, reversal, UUID.randomUUID(), variant));

        assertThat(refusal("UPDATE sale_reversal SET order_id = '" + UUID.randomUUID() + "' WHERE id = '" + reversal + "'")).isNotNull();
        assertThat(refusal("DELETE FROM sale_reversal WHERE id = '" + reversal + "'")).isNotNull();
        assertThat(refusal("INSERT INTO sale_reversal (id, kind, order_id, reservation_id, occurred_at, request_digest, recorded_at, actor) "
                + "VALUES ('" + UUID.randomUUID() + "', 'CANCELLATION', '" + UUID.randomUUID() + "', NULL, now(), '" + "a".repeat(64) + "', now(), 'x')"))
                .as("a cancellation names the hold it undoes").contains("sale_reversal_cancellation_names_hold");
        assertThat(refusal("UPDATE returned_piece SET variant_id = '" + UUID.randomUUID() + "' WHERE piece_id = '" + piece + "'")).isNotNull();
        assertThat(refusal("UPDATE returned_piece SET outcome = 'RELEASED' WHERE piece_id = '" + piece + "'"))
                .as("an outcome comes whole: what, by which movement, when, by whom").contains("returned_piece_outcome_whole");
        assertThat(refusal("UPDATE returned_piece SET outcome = 'RELISTED', outcome_action_id = '" + UUID.randomUUID()
                + "', outcome_at = now(), outcome_actor = 'x' WHERE piece_id = '" + piece + "'")).as("relisted names its new listing").contains("returned_piece_child");
        assertThat(refusal("UPDATE returned_piece SET outcome = 'RELEASED', outcome_action_id = '" + UUID.randomUUID()
                + "', outcome_at = now(), outcome_actor = 'service:order' WHERE piece_id = '" + piece + "'")).isNull();
        assertThat(refusal("UPDATE returned_piece SET outcome = 'WRITTEN_OFF' WHERE piece_id = '" + piece + "'")).as("decided once").isNotNull();
        assertThat(refusal("DELETE FROM returned_piece WHERE piece_id = '" + piece + "'")).isNotNull();
        assertThat(refusal("TRUNCATE returned_piece CASCADE")).isNotNull();
    }

    @Test
    @DisplayName("outbox_catch_up exists, as the event log module's reference defines it")
    void catchUpTable() throws SQLException {
        assertThat(one("SELECT count(*) FROM information_schema.columns WHERE table_name = 'outbox_catch_up'")).isEqualTo("5");
    }
}
