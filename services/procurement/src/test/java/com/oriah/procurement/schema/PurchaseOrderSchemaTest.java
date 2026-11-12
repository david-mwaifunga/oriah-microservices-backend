package com.oriah.procurement.schema;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Purchase orders as the database itself enforces them, with raw SQL and no entity.
 *
 * <p>A purchase order is what Oriah placed with ONE supplier: one line per item,
 * and for each the quantity and the total that left the bank (the all-in figure,
 * CLIENT_DECISIONS 3.1). The rules a bad migration or a direct insert could break
 * are pinned here, by constraint name, so a refusal is known to be the intended one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class PurchaseOrderSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID supplier;

    @BeforeEach
    void aSupplier() {
        supplier = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, name, country) VALUES (?, ?, 'AE')", supplier, "Schema Supplier " + supplier);
    }

    private UUID order(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order (id, supplier_id, placed_on, status)
                VALUES (?, ?, current_date, ?)""", id, supplier, status);
        return id;
    }

    private void line(UUID order, int position, int quantity, String totalPaid) {
        jdbc.update("""
                INSERT INTO purchase_order_line (id, purchase_order_id, position, variant_id, quantity, total_paid,
                                                 sku, variant_title, kind)
                VALUES (?, ?, ?, ?, ?, ?::numeric, 'SKU-1', 'A ring', 'SELLABLE')""",
                UUID.randomUUID(), order, position, UUID.randomUUID(), quantity, totalPaid);
    }

    @Test
    @DisplayName("every order gets its own reference number, counting up, which the screens show as PO-000123")
    void referenceNumbersCountUp() {
        UUID first = order("OPEN");
        UUID second = order("OPEN");

        long a = jdbc.queryForObject("SELECT ref_no FROM purchase_order WHERE id = ?", Long.class, first);
        long b = jdbc.queryForObject("SELECT ref_no FROM purchase_order WHERE id = ?", Long.class, second);

        assertThat(b).isGreaterThan(a);
    }

    @Test
    @DisplayName("an order is OPEN, RECEIVED or CANCELLED and nothing else")
    void statusIsClosed() {
        assertThatThrownBy(() -> order("PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_purchase_order_status");
    }

    @Test
    @DisplayName("an order must name a supplier that exists")
    void supplierMustExist() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO purchase_order (id, supplier_id, placed_on, status)
                VALUES (?, ?, current_date, 'OPEN')""", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_purchase_order_supplier");
    }

    @Test
    @DisplayName("a line has at least one unit and a total paid above zero")
    void lineAmountsArePositive() {
        UUID id = order("OPEN");

        assertThatThrownBy(() -> line(id, 1, 0, "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_purchase_order_line_quantity");
        assertThatThrownBy(() -> line(id, 1, 1, "0.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_purchase_order_line_total");
        assertThatThrownBy(() -> line(id, 1, 1, "-5.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_purchase_order_line_total");
        line(id, 1, 1, "0.01");
    }

    @Test
    @DisplayName("a line's total is held to the ngwee: NUMERIC(12,2), so a finer amount is rounded by the database, never stored")
    void totalPaidIsWholeNgwee() {
        UUID id = order("OPEN");
        line(id, 1, 3, "10.005");

        BigDecimal stored = jdbc.queryForObject("SELECT total_paid FROM purchase_order_line WHERE purchase_order_id = ?",
                BigDecimal.class, id);
        assertThat(stored.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("positions are unique within an order, so a screen's row order is stable")
    void positionsAreUnique() {
        UUID id = order("OPEN");
        line(id, 1, 1, "10.00");

        assertThatThrownBy(() -> line(id, 1, 1, "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_purchase_order_line_position");
        line(id, 2, 1, "10.00");
    }

    @Test
    @DisplayName("a line says what it is: a kind of SELLABLE or PACKAGING, copied from the catalogue when it was ordered")
    void kindIsClosed() {
        UUID id = order("OPEN");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO purchase_order_line (id, purchase_order_id, position, variant_id, quantity, total_paid,
                                                 sku, variant_title, kind)
                VALUES (?, ?, 1, ?, 1, 1.00, 'S', 'T', 'SERVICE')""", UUID.randomUUID(), id, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_purchase_order_line_kind");
    }

    @Test
    @DisplayName("a line goes when its order goes: no orphan lines")
    void linesFollowTheirOrder() {
        UUID id = order("OPEN");
        line(id, 1, 1, "10.00");

        jdbc.update("DELETE FROM purchase_order WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order_line WHERE purchase_order_id = ?",
                Long.class, id)).isZero();
    }

    @Test
    @DisplayName("an order's consignment is not set when it is placed, and a RECEIVED or CANCELLED order cannot be edited")
    void receivedAndCancelledOrdersAreFrozen() {
        UUID id = order("OPEN");
        line(id, 1, 1, "10.00");
        jdbc.update("UPDATE purchase_order SET status = 'CANCELLED' WHERE id = ?", id);

        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_order SET note = 'changed' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("is frozen");
        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_order_line SET quantity = 9 WHERE purchase_order_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("is frozen");
        assertThatThrownBy(() -> line(id, 2, 1, "10.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("is frozen");
    }
}
