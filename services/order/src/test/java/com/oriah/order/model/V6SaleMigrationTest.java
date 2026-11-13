package com.oriah.order.model;

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
 * V6 turns an order into a hold on pieces and the owner of the sale (Phase 7): the hold it took at inventory, the payment claim the shopper submitted,
 * the settlement of confirmed money (the saga's memory), the quote and key that make a placement happen once, and the receipt. Every new column is
 * NULLABLE or defaulted: orders placed before V6 are legacy (no reservation, no hold) and stay readable, and money for one is held, never settled.
 *
 * <p>The database holds the rules that must survive a bug: one live order per bag, one placement per Idempotency-Key, one receipt number, one
 * settlement per confirmation and one live settlement per order.
 */
@Testcontainers
class V6SaleMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static final UUID LEGACY = UUID.randomUUID();

    @BeforeAll
    static void migrateWithALegacyOrderFromBeforeV6() throws Exception {
        migrateTo(MigrationVersion.fromVersion("5"));
        execute("INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency) VALUES ('%s', 'kc-alice', 'PAID', 100.00, 100.00, 'ZMW')".formatted(LEGACY));
        execute("INSERT INTO order_items (id, order_id, variant_id, title, quantity, unit_price) VALUES ('%s', '%s', '%s', 'Aurelia ring', 1, 100.00)"
                .formatted(UUID.randomUUID(), LEGACY, UUID.randomUUID()));
        migrateTo(MigrationVersion.LATEST);
    }

    private static UUID order(String keycloak, String status, String digest, String key) throws Exception {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency, quote_digest, idempotency_key)
                VALUES ('%s', '%s', '%s', 100.00, 100.00, 'ZMW', %s, %s)""".formatted(id, keycloak, status, digest == null ? "NULL" : "'" + digest + "'",
                key == null ? "NULL" : "'" + key + "'"));
        return id;
    }

    // ---------------------------------------------------------------- legacy

    @Test
    @DisplayName("an order placed before V6 is still there with every new column empty: no reservation, no hold, no claim, no receipt, and its item has no discount or product")
    void aLegacyOrderIsLeftAlone() throws Exception {
        assertThat(stringOf("SELECT status FROM orders WHERE id = '" + LEGACY + "'")).isEqualTo("PAID");
        for (String column : new String[] {"reservation_id", "hold_state", "held_at", "hold_expires_at", "quote_digest", "idempotency_key", "delivery_town_id",
                "delivery_payer", "claim_id", "claim_method", "claim_reference", "claim_submitted_at", "settling_confirmation_id", "paid_confirmation_id",
                "paid_at", "receipt_number"}) {
            assertThat(stringOf("SELECT " + column + "::text FROM orders WHERE id = '" + LEGACY + "'")).as(column).isNull();
        }
        assertThat(stringOf("SELECT hold_extension_pending::text FROM orders WHERE id = '" + LEGACY + "'")).isEqualTo("false");
        for (String column : new String[] {"product_id", "discount_per_piece", "promotion_id", "box_variant_id", "box_line_id"}) {
            assertThat(stringOf("SELECT " + column + "::text FROM order_items WHERE order_id = '" + LEGACY + "'")).as(column).isNull();
        }
    }

    // ---------------------------------------------------------------- the hold and the claim

    @Test
    @DisplayName("a hold is PENDING (placed, not yet confirmed by inventory), HELD, RELEASED or CONSUMED, and nothing else")
    void holdStates() throws Exception {
        for (String state : new String[] {"PENDING", "HELD", "RELEASED", "CONSUMED"}) {
            execute("INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency, hold_state) VALUES ('%s', 'kc-x', 'CREATED', 1, 1, 'ZMW', '%s')"
                    .formatted(UUID.randomUUID(), state));
        }
        assertThatThrownBy(() -> execute("INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency, hold_state) VALUES ('%s', 'kc-x', 'CREATED', 1, 1, 'ZMW', 'LOST')"
                .formatted(UUID.randomUUID()))).isInstanceOf(SQLException.class).hasMessageContaining("orders_hold_state_known");
    }

    @Test
    @DisplayName("a payment claim is whole or absent (id, method, reference and time together), of a method that can be claimed online (never cash)")
    void claims() throws Exception {
        UUID id = order("kc-claims", "CREATED", null, null);

        execute("UPDATE orders SET claim_id = '%s', claim_method = 'MTN', claim_reference = 'MP2411ABC', claim_submitted_at = now() WHERE id = '%s'".formatted(UUID.randomUUID(), id));
        assertThatThrownBy(() -> execute("UPDATE orders SET claim_id = '%s' WHERE id = '%s'".formatted(UUID.randomUUID(), order("kc-claims", "CREATED", null, null))))
                .isInstanceOf(SQLException.class).hasMessageContaining("orders_claim_whole");
        assertThatThrownBy(() -> execute("UPDATE orders SET claim_id = '%s', claim_method = 'CASH', claim_reference = 'ABC123', claim_submitted_at = now() WHERE id = '%s'"
                .formatted(UUID.randomUUID(), order("kc-claims", "CREATED", null, null)))).isInstanceOf(SQLException.class).hasMessageContaining("orders_claim_method_known");
    }

    @Test
    @DisplayName("delivery is paid by Oriah's collecting it or by the recipient to the courier, and nothing else")
    void deliveryPayer() throws Exception {
        execute("UPDATE orders SET delivery_payer = 'RECIPIENT_PAYS' WHERE id = '%s'".formatted(order("kc-payer", "CREATED", null, null)));
        assertThatThrownBy(() -> execute("UPDATE orders SET delivery_payer = 'NOBODY' WHERE id = '%s'".formatted(order("kc-payer", "CREATED", null, null))))
                .isInstanceOf(SQLException.class).hasMessageContaining("orders_delivery_payer_known");
    }

    // ---------------------------------------------------------------- once

    @Test
    @DisplayName("one live order per bag: the same shopper and the same quote cannot hold the pieces twice while CREATED or PAYMENT_SUBMITTED, but may again once the first is finished")
    void oneLiveOrderPerBag() throws Exception {
        String digest = "d".repeat(64);
        UUID first = order("kc-bag", "CREATED", digest, null);

        assertThatThrownBy(() -> order("kc-bag", "CREATED", digest, null)).isInstanceOf(SQLException.class).hasMessageContaining("uq_orders_live_bag");
        assertThatThrownBy(() -> order("kc-bag", "PAYMENT_SUBMITTED", digest, null)).isInstanceOf(SQLException.class).hasMessageContaining("uq_orders_live_bag");
        order("kc-other-shopper", "CREATED", digest, null);
        execute("UPDATE orders SET status = 'EXPIRED' WHERE id = '%s'".formatted(first));
        order("kc-bag", "CREATED", digest, null);
        order("kc-bag", "EXPIRED", digest, null);
    }

    @Test
    @DisplayName("one placement per Idempotency-Key and shopper: a repeat is the same order, never a second")
    void oneOrderPerKey() throws Exception {
        order("kc-key", "CREATED", "a".repeat(64), "key-1");

        assertThatThrownBy(() -> order("kc-key", "CREATED", "b".repeat(64), "key-1")).isInstanceOf(SQLException.class).hasMessageContaining("uq_orders_idempotency");
        order("kc-key-2", "CREATED", "c".repeat(64), "key-1");
        order("kc-key", "CREATED", "e".repeat(64), "key-2");
    }

    @Test
    @DisplayName("a receipt number belongs to one order; a paid order carries its confirmation, its arrival date and its receipt together (and a legacy paid order carries none)")
    void receipts() throws Exception {
        UUID one = order("kc-receipt", "PAID", null, null);
        UUID two = order("kc-receipt", "PAID", null, null);
        UUID confirmation = UUID.randomUUID();
        execute("UPDATE orders SET paid_confirmation_id = '%s', paid_at = now(), receipt_number = 'R-000001' WHERE id = '%s'".formatted(confirmation, one));

        assertThatThrownBy(() -> execute("UPDATE orders SET paid_confirmation_id = '%s', paid_at = now(), receipt_number = 'R-000001' WHERE id = '%s'"
                .formatted(UUID.randomUUID(), two))).isInstanceOf(SQLException.class).hasMessageContaining("orders_receipt_number_key");
        assertThatThrownBy(() -> execute("UPDATE orders SET receipt_number = 'R-000002' WHERE id = '%s'".formatted(two))).isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_paid_whole");
        assertThatThrownBy(() -> execute("UPDATE orders SET paid_at = now() WHERE id = '%s'".formatted(two))).isInstanceOf(SQLException.class)
                .hasMessageContaining("orders_paid_whole");
        assertThat(longOf("SELECT nextval('order_receipt_seq')")).as("the receipt sequence exists and starts at 1").isEqualTo(1L);
    }

    // ---------------------------------------------------------------- the line

    @Test
    @DisplayName("a line may carry a discount per piece (never negative), the promotion that gave it, its product and its box")
    void lines() throws Exception {
        UUID id = order("kc-line", "CREATED", null, null);
        String insert = "INSERT INTO order_items (id, order_id, variant_id, title, quantity, unit_price, product_id, discount_per_piece, promotion_id, box_variant_id, box_line_id) "
                + "VALUES ('%s', '%s', '%s', 'Ring', 1, 100.00, '%s', %s, %s, '%s', '%s')";

        execute(insert.formatted(UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID(), "10.00", "'" + UUID.randomUUID() + "'", UUID.randomUUID(), UUID.randomUUID()));
        assertThatThrownBy(() -> execute(insert.formatted(UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID(), "-1.00", "NULL", UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(SQLException.class).hasMessageContaining("order_items_discount_check");
    }

    // ---------------------------------------------------------------- the settlement saga's memory

    private static void settlement(UUID order, UUID confirmation, String state) throws Exception {
        // An UNSETTLED settlement always says why; the other states have no reason.
        execute("""
                INSERT INTO order_settlement (id, order_id, confirmation_id, state, reason, method, reference, gross, fee, net, currency, arrived_at, confirmed_by, received_at)
                VALUES ('%s', '%s', '%s', '%s', %s, 'MTN', 'MP2411ABC', 1000.00, 10.00, 990.00, 'ZMW', now(), 'user:kc-sam', now())"""
                .formatted(UUID.randomUUID(), order, confirmation, state, "UNSETTLED".equals(state) ? "'WRONG_AMOUNT'" : "NULL"));
    }

    @Test
    @DisplayName("a confirmation is settled once, and an order has one live settlement at a time: UNSETTLED ones do not count, so money for an order that cannot take it is still recorded")
    void settlements() throws Exception {
        UUID order = UUID.randomUUID();
        UUID confirmation = UUID.randomUUID();
        settlement(order, confirmation, "CONSUMING");

        assertThatThrownBy(() -> settlement(order, confirmation, "UNSETTLED")).isInstanceOf(SQLException.class).hasMessageContaining("order_settlement_confirmation_id_key");
        assertThatThrownBy(() -> settlement(order, UUID.randomUUID(), "RECEIVED")).isInstanceOf(SQLException.class).hasMessageContaining("uq_order_settlement_live");
        assertThatThrownBy(() -> settlement(order, UUID.randomUUID(), "DONE")).isInstanceOf(SQLException.class).hasMessageContaining("uq_order_settlement_live");
        settlement(order, UUID.randomUUID(), "UNSETTLED");
        settlement(order, UUID.randomUUID(), "UNSETTLED");
        execute("UPDATE order_settlement SET state = 'UNSETTLED', reason = 'PIECE_SOLD' WHERE confirmation_id = '%s'".formatted(confirmation));
        settlement(order, UUID.randomUUID(), "BLOCKED_BOX");
    }

    @Test
    @DisplayName("a settlement has a state and a reason from the closed lists, an amount above zero and a deferred end only of the two kinds order defers")
    void settlementValues() throws Exception {
        UUID confirmation = UUID.randomUUID();
        settlement(UUID.randomUUID(), confirmation, "CONSUMING");

        assertThatThrownBy(() -> settlement(UUID.randomUUID(), UUID.randomUUID(), "LOST")).isInstanceOf(SQLException.class).hasMessageContaining("order_settlement_state_known");
        // VOIDED is not a reason payment is ever told (payment voided the money itself): it is the mark order keeps when a confirmation was voided before
        // it could be applied, so a late announcement of the same confirmation can never settle a sale.
        for (String reason : new String[] {"WRONG_AMOUNT", "ORDER_UNKNOWN", "ORDER_CANCELLED", "ALREADY_PAID", "PIECE_SOLD", "VOIDED"}) {
            execute("UPDATE order_settlement SET state = 'UNSETTLED', reason = '%s' WHERE confirmation_id = '%s'".formatted(reason, confirmation));
        }
        assertThatThrownBy(() -> execute("UPDATE order_settlement SET reason = 'BECAUSE' WHERE confirmation_id = '%s'".formatted(confirmation)))
                .isInstanceOf(SQLException.class).hasMessageContaining("order_settlement_reason_known");
        assertThatThrownBy(() -> execute("UPDATE order_settlement SET state = 'UNSETTLED', reason = NULL WHERE confirmation_id = '%s'".formatted(confirmation)))
                .isInstanceOf(SQLException.class).hasMessageContaining("order_settlement_unsettled_has_reason");
        assertThat(stringOf("SELECT voided_at::text FROM order_settlement WHERE confirmation_id = '" + confirmation + "'")).as("not voided").isNull();
        execute("UPDATE order_settlement SET voided_at = now() WHERE confirmation_id = '%s'".formatted(confirmation));
        assertThat(stringOf("SELECT voided_at::text FROM order_settlement WHERE confirmation_id = '" + confirmation + "'")).as("voided_at records the void").isNotNull();
        execute("UPDATE order_settlement SET deferred_end = 'EXPIRED' WHERE confirmation_id = '%s'".formatted(confirmation));
        assertThatThrownBy(() -> execute("UPDATE order_settlement SET deferred_end = 'LAPSED' WHERE confirmation_id = '%s'".formatted(confirmation)))
                .isInstanceOf(SQLException.class).hasMessageContaining("order_settlement_deferred_end_known");
        assertThatThrownBy(() -> execute("UPDATE order_settlement SET gross = 0 WHERE confirmation_id = '%s'".formatted(confirmation)))
                .isInstanceOf(SQLException.class).hasMessageContaining("order_settlement_gross_positive");
    }

    // ---------------------------------------------------------------- the event log is untouched

    @Test
    @DisplayName("V6 leaves the event log's own tables as the module's reference made them: the outbox and the inbox are still append-only")
    void theEventLogIsUntouched() throws Exception {
        assertThatThrownBy(() -> execute("TRUNCATE outbox")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("TRUNCATE processed_event")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- plumbing

    private static void migrateTo(MigrationVersion version) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(version).load().migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static String stringOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long longOf(String sql) throws Exception {
        return Long.parseLong(stringOf(sql));
    }
}
