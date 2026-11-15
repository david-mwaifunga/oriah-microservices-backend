package com.oriah.order.model;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * V13: an order staff record for a caller with no account (Phase 9, design r4 section 2).
 *
 * <p>The owner column becomes nullable, but only for the PHONE channel, and every rule that makes a phone order well-formed is the DATABASE's, so no
 * code path (a defect, a future channel, SQL run by hand) can store an ownerless web order or a phone order with no recorder. Each test migrates its own
 * fresh schema on a real Postgres; the legacy test migrates to V12 first, inserts an order the way V12 stores one, and then migrates on.
 */
class V13PhoneOrderMigrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    private static final String HASH = "a".repeat(64);

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
        schema = "v13_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
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

    /** A valid web order row, as it is stored today. */
    private static Map<String, Object> web() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.randomUUID());
        m.put("keycloak_id", "kc-alice");
        m.put("status", "CREATED");
        m.put("subtotal", new java.math.BigDecimal("4500.00"));
        m.put("total", new java.math.BigDecimal("4500.00"));
        m.put("currency", "ZMW");
        m.put("shipping_amount", new java.math.BigDecimal("0.00"));
        m.put("discount_amount", new java.math.BigDecimal("0.00"));
        m.put("delivery_option", "SELF_COLLECT");
        m.put("totals_authoritative", true);
        return m;
    }

    /** A valid phone order row: a pickup, recorded by staff, for a caller with no account. */
    private static Map<String, Object> phone() {
        Map<String, Object> m = web();
        m.put("keycloak_id", null);
        m.put("channel", "PHONE");
        m.put("recorded_by", "user:kc-sam");
        m.put("caller_name", "Mulenga Chanda");
        m.put("caller_phone", "+260971234567");
        m.put("sale_kind", "LIVE_PHONE");
        m.put("request_hash", HASH);
        m.put("idempotency_key", "key-" + UUID.randomUUID());
        return m;
    }

    /** A valid phone order for a delivery agreed by phone: Oriah collects K60.00 for its own delivery. */
    private static Map<String, Object> phoneDelivered() {
        Map<String, Object> m = phone();
        m.put("delivery_option", "OWN_DELIVERY");
        m.put("delivery_payer", "ORIAH_COLLECTS");
        m.put("delivery_town_id", UUID.randomUUID());
        m.put("shipping_amount", new java.math.BigDecimal("60.00"));
        m.put("total", new java.math.BigDecimal("4560.00"));
        m.put("delivery_price_source", "AGREED");
        m.put("delivery_agreed_amount", new java.math.BigDecimal("60.00"));
        m.put("recipient_name", "Mulenga Chanda");
        m.put("recipient_phone", "+260971234567");
        m.put("address_line1", "Plot 12 Great East Road");
        m.put("address_city", "Lusaka");
        m.put("address_country", "ZM");
        return m;
    }

    private void insert(Map<String, Object> row) throws SQLException {
        List<String> cols = new ArrayList<>(row.keySet());
        String sql = "INSERT INTO orders (" + String.join(", ", cols) + ") VALUES (" + "?, ".repeat(cols.size() - 1) + "?)";
        try (Connection c = connect(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < cols.size(); i++) {
                Object v = row.get(cols.get(i));
                if (v instanceof Instant t) {
                    ps.setTimestamp(i + 1, Timestamp.from(t));
                } else {
                    ps.setObject(i + 1, v);
                }
            }
            ps.executeUpdate();
        }
    }

    private static Map<String, Object> with(Map<String, Object> row, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private void refused(Map<String, Object> row, String constraint) {
        assertThatThrownBy(() -> insert(row)).as("refused by " + constraint).hasMessageContaining(constraint);
    }

    /** Postgres names the first failing CHECK in alphabetical order; where two rules both refuse a row, either name will do. */
    private void refusedByAny(Map<String, Object> row, String... constraints) {
        assertThatThrownBy(() -> insert(row)).satisfies(e -> assertThat(e.getMessage()).containsAnyOf(constraints));
    }

    @Test
    @DisplayName("an order stored before V13 migrates as a WEB order with its owner, dated to when it was placed, and its delivery price as published")
    void aLegacyOrderMigratesAsWeb() throws SQLException {
        flyway("12").migrate();
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO orders (id, keycloak_id, status, subtotal, total, currency, placed_at) "
                    + "VALUES ('" + id + "', 'kc-alice', 'PAID', 250.00, 250.00, 'ZMW', '2026-11-01T08:00:00Z')");
        }

        flyway(null).migrate();

        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT channel, keycloak_id, occurred_at, placed_at, delivery_price_source, recorded_by, caller_name FROM orders WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("channel")).isEqualTo("WEB");
            assertThat(rs.getString("keycloak_id")).isEqualTo("kc-alice");
            assertThat(rs.getTimestamp("occurred_at")).isEqualTo(rs.getTimestamp("placed_at"));
            assertThat(rs.getString("delivery_price_source")).isEqualTo("PUBLISHED");
            assertThat(rs.getString("recorded_by")).isNull();
            assertThat(rs.getString("caller_name")).isNull();
        }
    }

    @Test
    @DisplayName("a row that does not say its channel is a WEB order (raw inserts of older code keep working)")
    void theDefaultChannelIsWeb() throws SQLException {
        flyway(null).migrate();
        Map<String, Object> row = web();
        insert(row);

        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT channel FROM orders WHERE id = '" + row.get("id") + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("channel")).isEqualTo("WEB");
        }
    }

    @Test
    @DisplayName("a PHONE order saves with no account; a WEB order with no account, a blank one or an unknown channel is refused by name")
    void theOwnerFollowsTheChannel() throws SQLException {
        flyway(null).migrate();

        insert(phone());

        refused(with(web(), "keycloak_id", null), "orders_owner_by_channel");
        refused(with(web(), "keycloak_id", "  "), "orders_owner_by_channel");
        refused(with(phone(), "keycloak_id", "kc-alice"), "orders_owner_by_channel");
        refused(with(phone(), "channel", "WALKIN"), "orders_channel_known");
    }

    @Test
    @DisplayName("a WEB order carries nothing of a phone order: not a recorder, a caller, a sale kind, a hash, a slip, an agreed delivery")
    void aWebOrderIsNotAPhoneOrder() throws SQLException {
        flyway(null).migrate();

        for (Object[] extra : new Object[][] {{"recorded_by", "user:kc-sam"}, {"caller_name", "Mulenga Chanda"}, {"caller_phone", "+260971234567"},
                {"sale_kind", "LIVE_PHONE"}, {"request_hash", HASH}, {"slip_reference", "P-1"}, {"backdate_reason", "found in the drawer"}}) {
            refusedByAny(with(web(), extra[0], extra[1]), "orders_phone_record", "orders_late_reason");
        }
        refusedByAny(with(web(), "delivery_price_source", "AGREED"), "orders_phone_record", "orders_agreed_delivery");
    }

    @Test
    @DisplayName("a PHONE order needs its recorder, its caller's name, its sale kind and its request hash; the caller's phone may be missing only for a pickup")
    void aPhoneOrderIsWhole() throws SQLException {
        flyway(null).migrate();

        for (String missing : new String[] {"recorded_by", "caller_name", "sale_kind", "request_hash"}) {
            refused(with(phone(), missing, null), "orders_phone_record");
        }
        insert(with(phone(), "caller_phone", null, "idempotency_key", "pickup-no-phone"));
        refused(with(phoneDelivered(), "caller_phone", null), "orders_phone_record");
    }

    @Test
    @DisplayName("a caller's phone is '+' and 8 to 15 digits, and a name is not blank")
    void theCallerHasTheShapeOfAPerson() throws SQLException {
        flyway(null).migrate();

        for (String bad : new String[] {"0971234567", "+26097", "+260 97 1234", "abc", "+26097a234567"}) {
            refused(with(phone(), "caller_phone", bad), "orders_caller_shape");
        }
        refused(with(phone(), "caller_name", "   "), "orders_caller_shape");
        for (String bad : new String[] {"short", "A".repeat(64), "g".repeat(64)}) {
            refused(with(phone(), "request_hash", bad), "orders_request_hash_shape");
        }
    }

    @Test
    @DisplayName("a late paper sale carries its reason (5 to 200 characters) and a live sale carries none")
    void aLateSaleHasItsReason() throws SQLException {
        flyway(null).migrate();

        insert(with(phone(), "sale_kind", "LATE_PAPER", "backdate_reason", "found in the shop drawer"));
        refused(with(phone(), "sale_kind", "LATE_PAPER"), "orders_late_reason");
        refused(with(phone(), "sale_kind", "LATE_PAPER", "backdate_reason", "four"), "orders_late_reason");
        refused(with(phone(), "backdate_reason", "not for a live sale"), "orders_late_reason");
        refused(with(phone(), "sale_kind", "TELEPATHY"), "orders_sale_kind_known");
    }

    @Test
    @DisplayName("a sale cannot have happened more than five minutes after it was keyed")
    void aSaleIsNotInTheFuture() throws SQLException {
        flyway(null).migrate();

        insert(with(phone(), "occurred_at", Instant.now().minusSeconds(86400 * 10)));
        refused(with(phone(), "occurred_at", Instant.now().plusSeconds(3600)), "orders_occurred_not_future");
    }

    @Test
    @DisplayName("the delivery price source is PUBLISHED or AGREED; AGREED is for a PHONE order that delivers, to a town, with an amount that matches who pays the courier")
    void anAgreedDeliveryIsWhatItSaysItIs() throws SQLException {
        flyway(null).migrate();

        insert(phoneDelivered());
        refused(with(phoneDelivered(), "delivery_price_source", "AGRED", "delivery_agreed_amount", null), "orders_delivery_source_known");
        refused(with(phoneDelivered(), "delivery_agreed_amount", null), "orders_agreed_delivery");
        refused(with(phoneDelivered(), "delivery_town_id", null), "orders_agreed_delivery");
        refused(with(phoneDelivered(), "delivery_agreed_amount", new java.math.BigDecimal("0.00")), "orders_agreed_delivery");
        refused(with(phoneDelivered(), "delivery_payer", "RECIPIENT_PAYS"), "orders_agreed_delivery");
        refused(with(phoneDelivered(), "delivery_agreed_reason", "four"), "orders_agreed_delivery");
        refused(with(phoneDelivered(), "delivery_option", "SELF_COLLECT"), "orders_agreed_delivery");
        insert(with(phoneDelivered(), "delivery_option", "YANGO", "delivery_payer", "RECIPIENT_PAYS", "shipping_amount", new java.math.BigDecimal("0.00"),
                "total", new java.math.BigDecimal("4500.00"), "delivery_agreed_amount", new java.math.BigDecimal("0.00"), "idempotency_key", "yango-k"));
        refused(with(phone(), "delivery_agreed_amount", new java.math.BigDecimal("60.00")), "orders_agreed_delivery");
        refused(with(phone(), "delivery_agreed_reason", "no price source"), "orders_agreed_delivery");
    }

    @Test
    @DisplayName("waiving an agreed delivery leaves the agreement alone: the live charge falls to K0.00 and the V13 rules still hold")
    void aWaiverDoesNotBreakAnAgreedDelivery() throws SQLException {
        flyway(null).migrate();
        Map<String, Object> row = phoneDelivered();
        insert(row);

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE orders SET shipping_amount = 0.00, total = 4500.00, delivery_waived_amount = 60.00, delivery_waived_by = 'user:kc-maya', "
                    + "delivery_waiver_reason = 'a regular customer', delivery_waived_at = now() WHERE id = '" + row.get("id") + "'");
            ResultSet rs = s.executeQuery("SELECT delivery_agreed_amount, shipping_amount FROM orders WHERE id = '" + row.get("id") + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("delivery_agreed_amount")).isEqualByComparingTo("60.00");
            assertThat(rs.getBigDecimal("shipping_amount")).isEqualByComparingTo("0.00");
        }
    }

    @Test
    @DisplayName("a PHONE order that delivers has its recipient and address, until it is cancelled (a cancelled order forgets them); a pickup needs none")
    void aPhoneDeliveryIsWhole() throws SQLException {
        flyway(null).migrate();

        for (String missing : new String[] {"recipient_name", "recipient_phone", "address_line1"}) {
            Map<String, Object> row = phoneDelivered();
            row.put(missing, null);
            if (missing.equals("address_line1")) {
                row.put("address_city", null);
                row.put("address_country", null);
            }
            if (missing.startsWith("recipient")) {
                row.put("recipient_name", null);
                row.put("recipient_phone", null);
            }
            refused(row, "orders_phone_delivery_whole");
        }
        Map<String, Object> cancelled = with(phoneDelivered(), "status", "CANCELLED", "recipient_name", null, "recipient_phone", null, "address_line1", null,
                "address_city", null, "address_country", null);
        insert(cancelled);
        Map<String, Object> kept = with(phone(), "status", "CANCELLED", "idempotency_key", "cancelled-k");
        insert(kept);
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT caller_name FROM orders WHERE id = '" + kept.get("id") + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("caller_name")).as("a cancelled phone order keeps its caller: money may still arrive and need returning").isEqualTo("Mulenga Chanda");
        }
    }

    @Test
    @DisplayName("an idempotency key is used once among phone orders, whoever recorded them; web orders are unaffected")
    void aKeyIsUsedOnceAmongPhoneOrders() throws SQLException {
        flyway(null).migrate();

        insert(with(phone(), "idempotency_key", "same-key"));
        refused(with(phone(), "idempotency_key", "same-key", "recorded_by", "user:kc-other"), "uq_orders_phone_idempotency");
        insert(with(web(), "idempotency_key", "same-key"));
        insert(with(web(), "idempotency_key", "same-key", "keycloak_id", "kc-bob"));
    }
}
