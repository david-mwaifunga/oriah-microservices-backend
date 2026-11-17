package com.oriah.order.model;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V16: a paid sale is corrected, never edited (Phase 12, plan 3.1; FINAL AV6 and AV12). A correction (a cancellation before dispatch, or a return after
 * it) is a record of its own that points back at the sale's lines; the paid order's rows are never rewritten. Every rule that makes a correction
 * well-formed is the DATABASE's, so no code path can store a debt that does not add up, a line the sale never had, or more pieces than were sold.
 * Each test migrates its own fresh schema on a real Postgres.
 */
class V16CorrectionsMigrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    private static final Instant SOLD = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-05T09:15:00Z");

    private String schema;
    private UUID order;
    private UUID line;
    private UUID variant;
    private UUID product;
    private UUID promotion;

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    /** A paid order of 2 rings at K100.00, K10.00 off each, delivery K45.00: K225.00. */
    @BeforeEach
    void aPaidOrder() throws SQLException {
        schema = "v16_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        order = UUID.randomUUID();
        line = UUID.randomUUID();
        variant = UUID.randomUUID();
        product = UUID.randomUUID();
        promotion = UUID.randomUUID();
        exec("""
                INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency, shipping_amount, discount_amount, delivery_option, totals_authoritative)
                VALUES (?, 'kc-alice', 'PAID', 200.00, 225.00, 'ZMW', 45.00, 20.00, 'OWN_DELIVERY', TRUE)""", order);
        exec("""
                INSERT INTO order_items (id, order_id, variant_id, title, quantity, unit_price, product_id, discount_per_piece, promotion_id)
                VALUES (?, ?, ?, 'Aurelia ring', 2, 100.00, ?, 10.00, ?)""", line, order, variant, product, promotion);
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + schema);
        }
        return c;
    }

    private int exec(String sql, Object... args) throws SQLException {
        try (Connection c = connect(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i] instanceof Instant t ? Timestamp.from(t) : args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private Object one(String sql, Object... args) throws SQLException {
        try (Connection c = connect(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }

    /** A cancellation of the whole order, in flight: K200.00 of goods, K20.00 of discounts, K45.00 of delivery, K225.00 owed. */
    private Map<String, Object> cancellation() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.randomUUID());
        m.put("order_id", order);
        m.put("kind", "CANCELLATION");
        m.put("state", "REQUESTED");
        m.put("reason_code", "CUSTOMER_ASKED");
        m.put("status_before", "PAID");
        m.put("confirmation_id", UUID.randomUUID());
        m.put("receipt_number", "OR-000123");
        m.put("sale_arrived_at", SOLD);
        m.put("occurred_at", NOW);
        m.put("goods_at_list", new BigDecimal("200.00"));
        m.put("discounts", new BigDecimal("20.00"));
        m.put("delivery_refunded", new BigDecimal("45.00"));
        m.put("waiver_taken_back", new BigDecimal("0.00"));
        m.put("owed_gross", new BigDecimal("225.00"));
        m.put("currency", "ZMW");
        m.put("fee_bearer", "CUSTOMER");
        m.put("recorded_by", "user:kc-maya");
        m.put("recorded_at", NOW);
        return m;
    }

    /** A return of one ring after delivery, in flight: K100.00 less K10.00, K90.00 owed, keyed and fingerprinted. */
    private Map<String, Object> aReturn() {
        Map<String, Object> m = cancellation();
        m.put("kind", "RETURN");
        m.put("reason_code", "CHANGED_MIND");
        m.put("status_before", "DELIVERED");
        m.put("goods_at_list", new BigDecimal("100.00"));
        m.put("discounts", new BigDecimal("10.00"));
        m.put("delivery_refunded", new BigDecimal("0.00"));
        m.put("owed_gross", new BigDecimal("90.00"));
        m.put("idempotency_key", "key-" + UUID.randomUUID());
        m.put("request_hash", "a".repeat(64));
        return m;
    }

    private static Map<String, Object> with(Map<String, Object> row, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private UUID insert(Map<String, Object> row) throws SQLException {
        String cols = String.join(", ", row.keySet());
        exec("INSERT INTO order_correction (" + cols + ") VALUES (" + "?, ".repeat(row.size() - 1) + "?)", row.values().toArray());
        return (UUID) row.get("id");
    }

    private void lineOf(UUID correction, int quantity) throws SQLException {
        exec("""
                INSERT INTO order_correction_line (correction_id, sale_line_id, variant_id, product_id, quantity, unit_price, discount_per_piece, promotion_id)
                VALUES (?, ?, ?, ?, ?, 100.00, 10.00, ?)""", correction, line, variant, product, quantity, promotion);
    }

    private UUID piece(UUID correction, int pieceNo) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO order_return_piece (id, correction_id, sale_line_id, piece_no) VALUES (?, ?, ?, ?)", id, correction, line, pieceNo);
        return id;
    }

    private void refused(String constraintOrMessage, ThrowingCall call) {
        assertThatThrownBy(call::run).isInstanceOf(SQLException.class).hasMessageContaining(constraintOrMessage);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ the correction

    @Test
    void amountsAddUp() throws SQLException {
        insert(cancellation());

        refused("order_correction_owed", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "owed_gross", new BigDecimal("224.99"))));
        refused("order_correction_amounts", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "discounts", new BigDecimal("200.01"),
                "owed_gross", new BigDecimal("44.99"))));
        // A return gives back goods only: never delivery, never a waiver.
        refused("order_correction_return_goods_only", () -> insert(with(aReturn(), "order_id", UUID.randomUUID(), "delivery_refunded", new BigDecimal("45.00"),
                "owed_gross", new BigDecimal("135.00"))));
        // A delivery that was waived was never paid: it is taken back from the memo or refunded, never both.
        refused("order_correction_waiver_or_charge", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "waiver_taken_back", new BigDecimal("45.00"))));
        refused("order_correction_not_before_sale", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "occurred_at", SOLD.minusSeconds(1))));
        refused("order_correction_bearer_known", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "fee_bearer", "NOBODY")));
    }

    @Test
    void theReasonAndTheStatusBelongToTheKind() throws SQLException {
        insert(aReturn());

        refused("order_correction_reason_known", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "reason_code", "CHANGED_MIND")));
        refused("order_correction_reason_known", () -> insert(with(aReturn(), "order_id", UUID.randomUUID(), "reason_code", "PIECE_UNAVAILABLE")));
        refused("order_correction_status_before", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "status_before", "SHIPPED")));
        refused("order_correction_status_before", () -> insert(with(aReturn(), "order_id", UUID.randomUUID(), "status_before", "PAID")));
        // "Other" says why.
        refused("order_correction_other_says_why", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "reason_code", "OTHER")));
        refused("order_correction_note_length", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "reason_code", "OTHER", "note", "  no  ")));
        assertThatCode(() -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "reason_code", "OTHER", "note", "the owner asked for it")))
                .doesNotThrowAnyException();
        // Only a return is back-dated with a reason.
        refused("order_correction_backdate", () -> insert(with(cancellation(), "order_id", UUID.randomUUID(), "backdate_reason", "typed in late")));
    }

    /** AV6: a CHECK that compares a NULL passes. A return with no key, or no fingerprint, must be refused all the same. */
    @Test
    void aReturnAlwaysCarriesItsKeyAndACancellationNever() {
        refused("order_correction_key_by_kind", () -> insert(with(aReturn(), "idempotency_key", null)));
        refused("order_correction_key_by_kind", () -> insert(with(aReturn(), "request_hash", null)));
        refused("order_correction_key_by_kind", () -> insert(with(aReturn(), "request_hash", "not-a-hash")));
        refused("order_correction_key_by_kind", () -> insert(with(cancellation(), "idempotency_key", "a-key")));
        refused("order_correction_key_by_kind", () -> insert(with(cancellation(), "request_hash", "a".repeat(64))));
    }

    @Test
    void oneLiveCancellationPerOrderARefusedOneDoesNotCount() throws SQLException {
        UUID first = insert(cancellation());

        refused("uq_order_correction", () -> insert(cancellation()));

        exec("UPDATE order_correction SET state = 'REFUSED', refused_code = 'not-a-sale' WHERE id = ?", first);
        UUID second = insert(cancellation());
        exec("UPDATE order_correction SET state = 'DONE', completed_at = ? WHERE id = ?", NOW, second);

        // Done is still a cancellation of this order: there is never a second one.
        refused("uq_order_correction_cancellation", () -> insert(cancellation()));
    }

    @Test
    void oneInFlightPerOrder() throws SQLException {
        UUID first = insert(aReturn());

        refused("uq_order_correction_in_flight", () -> insert(aReturn()));

        exec("UPDATE order_correction SET state = 'DONE', completed_at = ? WHERE id = ?", NOW, first);
        assertThatCode(() -> insert(aReturn())).doesNotThrowAnyException();
        // A key is used once, whatever became of the return it recorded.
        Map<String, Object> sameKey = with(aReturn(), "order_id", UUID.randomUUID(), "idempotency_key",
                one("SELECT idempotency_key FROM order_correction WHERE id = ?", first));
        refused("uq_order_correction_key", () -> insert(sameKey));
    }

    @Test
    void aCorrectionIsARecord() throws SQLException {
        UUID id = insert(cancellation());

        refused("cannot be changed", () -> exec("UPDATE order_correction SET owed_gross = 1.00, goods_at_list = 1.00, discounts = 0, delivery_refunded = 0 WHERE id = ?", id));
        refused("cannot be changed", () -> exec("UPDATE order_correction SET fee_bearer = 'ORIAH' WHERE id = ?", id));
        refused("cannot be changed", () -> exec("UPDATE order_correction SET reason_code = 'SHOP_ERROR' WHERE id = ?", id));
        refused("order_correction", () -> exec("DELETE FROM order_correction WHERE id = ?", id));
        refused("order_correction", () -> exec("TRUNCATE order_correction CASCADE"));

        // What may move while it is in flight: the tries and when to try again.
        assertThat(exec("UPDATE order_correction SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?", NOW.plusSeconds(15), id)).isEqualTo(1);
        // Done has its time, refused its code, and never the other way round.
        refused("order_correction_done_timed", () -> exec("UPDATE order_correction SET state = 'DONE' WHERE id = ?", id));
        refused("order_correction_refused_code", () -> exec("UPDATE order_correction SET state = 'REFUSED' WHERE id = ?", id));
        assertThat(exec("UPDATE order_correction SET state = 'DONE', completed_at = ? WHERE id = ?", NOW, id)).isEqualTo(1);
        // Finished is final.
        refused("final", () -> exec("UPDATE order_correction SET state = 'REQUESTED', completed_at = NULL WHERE id = ?", id));
        refused("final", () -> exec("UPDATE order_correction SET attempts = 9 WHERE id = ?", id));
    }

    // ------------------------------------------------------------------ its lines

    @Test
    void aLineIsExactlyTheSale() throws SQLException {
        UUID id = insert(cancellation());
        String insert = """
                INSERT INTO order_correction_line (correction_id, sale_line_id, variant_id, product_id, quantity, unit_price, discount_per_piece, promotion_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

        // Not a line of this order.
        refused("is not a line of order", () -> exec(insert, id, UUID.randomUUID(), variant, product, 1, new BigDecimal("100.00"), new BigDecimal("10.00"), promotion));
        // Another price, another discount, another product, another variant, another promotion: none is what the sale line sold.
        refused("exactly what the sale line sold", () -> exec(insert, id, line, variant, product, 1, new BigDecimal("99.00"), new BigDecimal("10.00"), promotion));
        refused("exactly what the sale line sold", () -> exec(insert, id, line, variant, product, 1, new BigDecimal("100.00"), new BigDecimal("0.00"), promotion));
        refused("exactly what the sale line sold", () -> exec(insert, id, line, variant, UUID.randomUUID(), 1, new BigDecimal("100.00"), new BigDecimal("10.00"), promotion));
        refused("exactly what the sale line sold", () -> exec(insert, id, line, UUID.randomUUID(), product, 1, new BigDecimal("100.00"), new BigDecimal("10.00"), promotion));
        refused("exactly what the sale line sold", () -> exec(insert, id, line, variant, product, 1, new BigDecimal("100.00"), new BigDecimal("10.00"), null));

        // A box is named whole or not at all.
        refused("order_correction_line_box", () -> exec("""
                INSERT INTO order_correction_line (correction_id, sale_line_id, variant_id, product_id, quantity, unit_price, discount_per_piece, promotion_id, box_line_id)
                VALUES (?, ?, ?, ?, 1, 100.00, 10.00, ?, ?)""", id, line, variant, product, promotion, UUID.randomUUID()));
        lineOf(id, 2);
        // Lines are a record too.
        refused("order_correction_line", () -> exec("UPDATE order_correction_line SET quantity = 1 WHERE correction_id = ?", id));
        refused("order_correction_line", () -> exec("DELETE FROM order_correction_line WHERE correction_id = ?", id));
    }

    /** AV12: a sale line may carry a promotion that took K0.00 off; the correction copies it exactly and is not refused for it. */
    @Test
    void aPromotionThatTookNothingOffIsCopiedToo() throws SQLException {
        UUID plain = UUID.randomUUID();
        exec("""
                INSERT INTO order_items (id, order_id, variant_id, title, quantity, unit_price, product_id, discount_per_piece, promotion_id)
                VALUES (?, ?, ?, 'Plain band', 1, 50.00, ?, 0.00, ?)""", plain, order, variant, product, promotion);
        UUID id = insert(aReturn());

        assertThat(exec("""
                INSERT INTO order_correction_line (correction_id, sale_line_id, variant_id, product_id, quantity, unit_price, discount_per_piece, promotion_id)
                VALUES (?, ?, ?, ?, 1, 50.00, 0.00, ?)""", id, plain, variant, product, promotion)).isEqualTo(1);
    }

    @Test
    void neverMoreThanWasSold() throws SQLException {
        UUID first = insert(aReturn());
        lineOf(first, 1);
        exec("UPDATE order_correction SET state = 'DONE', completed_at = ? WHERE id = ?", NOW, first);

        // One of two has come back: two more is one too many, one more is the last.
        UUID second = insert(aReturn());
        refused("2 sold, 1 already corrected", () -> lineOf(second, 2));
        exec("UPDATE order_correction SET state = 'REFUSED', refused_code = 'not-a-sale' WHERE id = ?", second);

        // A refused correction took nothing: its pieces do not count.
        UUID third = insert(aReturn());
        lineOf(third, 1);
        exec("UPDATE order_correction SET state = 'DONE', completed_at = ? WHERE id = ?", NOW, third);

        UUID fourth = insert(aReturn());
        refused("2 sold, 2 already corrected", () -> lineOf(fourth, 1));
        refused("order_correction_line_quantity", () -> lineOf(fourth, 0));
    }

    // ------------------------------------------------------------------ the pieces of a return

    @Test
    void aPieceIsDecidedOnceAndWholly() throws SQLException {
        UUID id = insert(aReturn());
        lineOf(id, 2);
        UUID piece = piece(id, 1);
        UUID other = piece(id, 2);

        refused("order_return_piece_once", () -> piece(id, 2));
        refused("order_return_piece_no", () -> piece(id, 0));
        // A piece belongs to a line of ITS return.
        refused("order_return_piece", () -> exec("INSERT INTO order_return_piece (id, correction_id, sale_line_id, piece_no) VALUES (?, ?, ?, 1)",
                UUID.randomUUID(), id, UUID.randomUUID()));

        // Waiting means nothing is decided; a decision is made whole (the outcome, who and when).
        refused("order_return_piece_decided", () -> exec("UPDATE order_return_piece SET state = 'IN_PROGRESS' WHERE id = ?", piece));
        refused("order_return_piece_asked", () -> exec("UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = 'FINE' WHERE id = ?", piece));
        refused("order_return_piece_markdown", () -> exec(
                "UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = 'MARKED_DOWN', asked_by = 'user:kc-maya', asked_at = ? WHERE id = ?", NOW, piece));
        refused("order_return_piece_markdown", () -> exec(
                "UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = 'FINE', asked_by = 'user:kc-maya', asked_at = ?, markdown_variant_id = ? WHERE id = ?",
                NOW, UUID.randomUUID(), piece));
        refused("order_return_piece_why", () -> exec(
                "UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = 'UNSELLABLE', asked_by = 'user:kc-maya', asked_at = ? WHERE id = ?", NOW, piece));
        refused("order_return_piece_outcome", () -> exec(
                "UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = 'LOST', asked_by = 'user:kc-maya', asked_at = ? WHERE id = ?", NOW, piece));
        // Waiting never jumps to done.
        refused("order_return_piece", () -> exec(
                "UPDATE order_return_piece SET state = 'DONE', outcome = 'FINE', asked_by = 'user:kc-maya', asked_at = ?, assessed_at = ? WHERE id = ?", NOW, NOW, piece));

        assertThat(exec("UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = 'UNSELLABLE', note = 'the clasp is broken', asked_by = 'user:kc-maya', asked_at = ? WHERE id = ?",
                NOW, piece)).isEqualTo(1);
        // In progress, the decision cannot be swapped for another: it goes back to waiting whole, or forward to done.
        refused("order_return_piece", () -> exec("UPDATE order_return_piece SET outcome = 'FINE', note = NULL WHERE id = ?", piece));
        refused("order_return_piece_done", () -> exec("UPDATE order_return_piece SET state = 'DONE' WHERE id = ?", piece));
        assertThat(exec("UPDATE order_return_piece SET state = 'AWAITING', outcome = NULL, note = NULL, asked_by = NULL, asked_at = NULL, attempts = 0, next_attempt_at = NULL WHERE id = ?",
                piece)).isEqualTo(1);

        assertThat(exec("UPDATE order_return_piece SET state = 'IN_PROGRESS', outcome = 'MARKED_DOWN', markdown_variant_id = ?, asked_by = 'user:kc-maya', asked_at = ? WHERE id = ?",
                UUID.randomUUID(), NOW, other)).isEqualTo(1);
        assertThat(exec("UPDATE order_return_piece SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?", NOW.plusSeconds(15), other)).isEqualTo(1);
        assertThat(exec("UPDATE order_return_piece SET state = 'DONE', assessed_at = ? WHERE id = ?", NOW, other)).isEqualTo(1);
        // Done is final, and what the piece is never changes.
        refused("final", () -> exec("UPDATE order_return_piece SET state = 'AWAITING', outcome = NULL, markdown_variant_id = NULL, asked_by = NULL, asked_at = NULL, assessed_at = NULL WHERE id = ?", other));
        refused("order_return_piece", () -> exec("UPDATE order_return_piece SET piece_no = 9 WHERE id = ?", piece));
        refused("order_return_piece", () -> exec("DELETE FROM order_return_piece WHERE id = ?", piece));
    }

    // ------------------------------------------------------------------ the exchange link

    @Test
    void anExchangeLinksOneReturnToOneNewOrder() throws SQLException {
        UUID first = insert(aReturn());
        UUID newOrder = UUID.randomUUID();
        exec("INSERT INTO order_exchange (id, return_id, new_order_id, linked_by, linked_at) VALUES (?, ?, ?, 'user:kc-maya', ?)", UUID.randomUUID(), first, newOrder, NOW);

        refused("order_exchange_return_id_key", () -> exec("INSERT INTO order_exchange (id, return_id, new_order_id, linked_by, linked_at) VALUES (?, ?, ?, 'user:kc-maya', ?)",
                UUID.randomUUID(), first, UUID.randomUUID(), NOW));
        exec("UPDATE order_correction SET state = 'DONE', completed_at = ? WHERE id = ?", NOW, first);
        UUID second = insert(aReturn());
        refused("order_exchange_new_order_id_key", () -> exec("INSERT INTO order_exchange (id, return_id, new_order_id, linked_by, linked_at) VALUES (?, ?, ?, 'user:kc-maya', ?)",
                UUID.randomUUID(), second, newOrder, NOW));
        refused("order_exchange", () -> exec("UPDATE order_exchange SET new_order_id = ? WHERE return_id = ?", UUID.randomUUID(), first));
        refused("order_exchange", () -> exec("DELETE FROM order_exchange WHERE return_id = ?", first));
    }
}
