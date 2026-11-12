package com.oriah.procurement.schema;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consignments as the database itself enforces them, with raw SQL and no entity.
 *
 * <p>A consignment is one shipment: whole purchase orders that a forwarder brought in under
 * one bill (or the opening stock the shop already held on day one), with the costs that were
 * shared across them. The rules a bad migration or a direct insert could break are pinned
 * here, by constraint name, so a refusal is known to be the intended one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ConsignmentSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID supplier;

    @BeforeEach
    void aSupplier() {
        ProcurementTestData.wipe(jdbc);
        supplier = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, name, country) VALUES (?, 'Schema Supplier', 'AE')", supplier);
    }

    // ------------------------------------------------------------ fixtures

    private UUID consignment(String status, boolean opening, boolean estimated) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO consignment (id, status, is_opening, is_estimated, received_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? = 'RECEIVED' THEN now() ELSE NULL END)""",
                id, status, opening, estimated, status);
        return id;
    }

    private UUID draft() {
        return consignment("DRAFT", false, false);
    }

    private UUID openingDraft() {
        return consignment("DRAFT", true, true);
    }

    /** A purchase order line, and the order it is on. */
    private record Placed(UUID order, UUID line) {
    }

    private Placed orderLine() {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_order (id, supplier_id, placed_on, status) VALUES (?, ?, current_date, 'OPEN')",
                order, supplier);
        UUID line = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order_line (id, purchase_order_id, position, variant_id, quantity, total_paid,
                                                 sku, variant_title, kind)
                VALUES (?, ?, 1, ?, 2, 100.00, 'SKU-1', 'A ring', 'SELLABLE')""", line, order, UUID.randomUUID());
        return new Placed(order, line);
    }

    /** A line of a normal consignment: it came from a purchase order line, so it has a supplier. */
    private UUID normalLine(UUID consignment, int position, String goods) {
        return normalLine(consignment, position, goods, orderLine());
    }

    private UUID normalLine(UUID consignment, int position, String goods, Placed placed) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO consignment_line (id, consignment_id, is_opening, position, supplier_id, purchase_order_id,
                                              purchase_order_line_id, variant_id, sku, variant_title, kind, quantity, goods_total)
                VALUES (?, ?, false, ?, ?, ?, ?, ?, 'SKU-1', 'A ring', 'SELLABLE', 2, ?::numeric)""",
                id, consignment, position, supplier, placed.order(), placed.line(), UUID.randomUUID(), goods);
        return id;
    }

    /** A line of the opening consignment: typed in, no supplier, no purchase order. */
    private UUID openingLine(UUID consignment, int position, String goods) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO consignment_line (id, consignment_id, is_opening, position, supplier_id, purchase_order_id,
                                              purchase_order_line_id, variant_id, sku, variant_title, kind, quantity, goods_total)
                VALUES (?, ?, true, ?, NULL, NULL, NULL, ?, 'SKU-1', 'A ring', 'SELLABLE', 2, ?::numeric)""",
                id, consignment, position, UUID.randomUUID(), goods);
        return id;
    }

    private UUID costLine(UUID consignment, int position, String type, String amount, String basis, String treatment) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cost_line (id, consignment_id, position, cost_type, amount, basis, treatment)
                VALUES (?, ?, ?, ?, ?::numeric, ?, ?)""", id, consignment, position, type, amount, basis, treatment);
        return id;
    }

    // ------------------------------------------------------- the consignment

    @Test
    @DisplayName("every consignment gets its own reference number, counting up, which the screens show as C-000045")
    void referenceNumbersCountUp() {
        UUID first = draft();
        UUID second = draft();

        long a = jdbc.queryForObject("SELECT ref_no FROM consignment WHERE id = ?", Long.class, first);
        long b = jdbc.queryForObject("SELECT ref_no FROM consignment WHERE id = ?", Long.class, second);

        assertThat(b).isGreaterThan(a);
    }

    @Test
    @DisplayName("a consignment is a DRAFT or RECEIVED and nothing else")
    void statusIsClosed() {
        assertThatThrownBy(() -> consignment("ON_THE_WAY", false, false))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_status");
    }

    @Test
    @DisplayName("the opening consignment is always an estimate: its costs come from the client's old book, not an invoice")
    void openingIsAlwaysEstimated() {
        assertThatThrownBy(() -> consignment("DRAFT", true, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_consignment_opening_estimated");
        consignment("DRAFT", true, true);
        consignment("DRAFT", false, true);
    }

    @Test
    @DisplayName("a consignment has a receipt time if and only if it is RECEIVED")
    void receiptTimeGoesWithReceivedStatus() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO consignment (id, status, is_opening, is_estimated, received_at) VALUES (?, 'RECEIVED', false, false, NULL)",
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_received_at");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO consignment (id, status, is_opening, is_estimated, received_at) VALUES (?, 'DRAFT', false, false, now())",
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_received_at");
    }

    @Test
    @DisplayName("the charges basis is DUTY_PAID or FREIGHT_ONLY, the clearance form CE6 or CE20, and each may be left blank")
    void customsFieldsAreClosed() {
        UUID id = draft();
        jdbc.update("UPDATE consignment SET charges_basis = 'FREIGHT_ONLY', clearance_form = 'CE6' WHERE id = ?", id);
        jdbc.update("UPDATE consignment SET charges_basis = 'DUTY_PAID', clearance_form = 'CE20' WHERE id = ?", id);
        jdbc.update("UPDATE consignment SET charges_basis = NULL, clearance_form = NULL WHERE id = ?", id);

        assertThatThrownBy(() -> jdbc.update("UPDATE consignment SET charges_basis = 'MAYBE' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_charges_basis");
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment SET clearance_form = 'CE7' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_clearance_form");
    }

    // ------------------------------------------------------------- the lines

    @Test
    @DisplayName("a line of a normal consignment must come from a purchase order line and say which supplier; an opening line has neither")
    void aLineIsEitherFromAnOrderOrTypedIn() {
        UUID normal = draft();
        UUID opening = openingDraft();
        normalLine(normal, 1, "100.00");
        openingLine(opening, 1, "80.00");

        // A normal line with no purchase order behind it.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO consignment_line (id, consignment_id, is_opening, position, supplier_id, purchase_order_id,
                                              purchase_order_line_id, variant_id, sku, variant_title, kind, quantity, goods_total)
                VALUES (?, ?, false, 2, ?, NULL, NULL, ?, 'S', 'T', 'SELLABLE', 1, 5.00)""",
                UUID.randomUUID(), normal, supplier, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_origin");
        // An opening line that names a supplier.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO consignment_line (id, consignment_id, is_opening, position, supplier_id, purchase_order_id,
                                              purchase_order_line_id, variant_id, sku, variant_title, kind, quantity, goods_total)
                VALUES (?, ?, true, 2, ?, NULL, NULL, ?, 'S', 'T', 'SELLABLE', 1, 5.00)""",
                UUID.randomUUID(), opening, supplier, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_origin");
    }

    @Test
    @DisplayName("a line says it is an opening line only inside an opening consignment, and a normal one only inside a normal one")
    void aLineAgreesWithItsConsignmentAboutBeingOpening() {
        UUID normal = draft();
        UUID opening = openingDraft();

        // An opening-shaped line in a normal consignment.
        assertThatThrownBy(() -> openingLine(normal, 1, "80.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_consignment_line_consignment");
        // A normal-shaped line in the opening consignment.
        assertThatThrownBy(() -> normalLine(opening, 1, "100.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_consignment_line_consignment");
    }

    @Test
    @DisplayName("an opening line cannot cost nothing: the client approximates every piece from the old book, and a K0 cost is refused")
    void anOpeningLineHasACost() {
        UUID opening = openingDraft();

        assertThatThrownBy(() -> openingLine(opening, 1, "0.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_opening_cost");
        openingLine(opening, 1, "0.01");
    }

    @Test
    @DisplayName("a line has at least one unit, a cost of zero or more, a kind, and a whole number of grams if weighed")
    void lineAmountsAreSane() {
        UUID id = draft();
        UUID line = normalLine(id, 1, "0.00");
        assertThat(line).isNotNull();

        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET quantity = 0 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_quantity");
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET goods_total = -0.01 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_goods");
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET kind = 'GADGET' WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_kind");
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET chargeable_weight_grams = -1 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_weight");
        jdbc.update("UPDATE consignment_line SET chargeable_weight_grams = 1250 WHERE id = ?", line);
    }

    @Test
    @DisplayName("an origin country is two capital letters and an HS code is digits and dots, or each may be blank")
    void originAndHsCodeAreTidy() {
        UUID line = normalLine(draft(), 1, "10.00");

        jdbc.update("UPDATE consignment_line SET origin_country = 'CN', hs_code = '7113.19.00' WHERE id = ?", line);
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET origin_country = 'cn' WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_origin_country");
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET hs_code = 'gold' WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_consignment_line_hs_code");
    }

    @Test
    @DisplayName("a purchase order line is in at most one consignment line")
    void anOrderLineIsInOneConsignmentOnly() {
        Placed placed = orderLine();
        normalLine(draft(), 1, "100.00", placed);

        assertThatThrownBy(() -> normalLine(draft(), 1, "100.00", placed))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_consignment_line_po_line");
    }

    @Test
    @DisplayName("positions are unique within a consignment, checked at commit so lines can be renumbered")
    void positionsAreUnique() {
        UUID id = draft();
        normalLine(id, 1, "10.00");

        assertThatThrownBy(() -> normalLine(id, 1, "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_consignment_line_position");
        normalLine(id, 2, "10.00");
    }

    // ------------------------------------------------------------ cost lines

    @Test
    @DisplayName("a cost line is one of the closed list of inbound cost types, and nothing that is a bank or transfer charge")
    void costTypesAreClosed() {
        UUID id = draft();
        int position = 1;
        for (String type : new String[] {"COURIER_INBOUND", "FREIGHT", "INSURANCE", "PORT_INCIDENTALS",
                "INBOUND_TRANSPORT", "AGENT_FEE", "DUTY", "SURTAX", "IMPORT_VAT"}) {
            costLine(id, position++, type, "1.00", "DIRECT", "CAPITALIZE");
        }

        assertThatThrownBy(() -> costLine(id, 20, "BANK_CHARGE", "1.00", "UNITS", "CAPITALIZE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_cost_line_type");
        assertThatThrownBy(() -> costLine(id, 21, "TRANSFER_FEE", "1.00", "UNITS", "CAPITALIZE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_cost_line_type");
    }

    @Test
    @DisplayName("a cost line's basis is UNITS, VALUE, WEIGHT or DIRECT, its treatment CAPITALIZE or PERIOD, and its amount is not negative")
    void basisTreatmentAndAmount() {
        UUID id = draft();

        assertThatThrownBy(() -> costLine(id, 1, "FREIGHT", "1.00", "PER_KILO", "CAPITALIZE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_cost_line_basis");
        assertThatThrownBy(() -> costLine(id, 1, "FREIGHT", "1.00", "UNITS", "EXPENSE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_cost_line_treatment");
        assertThatThrownBy(() -> costLine(id, 1, "FREIGHT", "-1.00", "UNITS", "CAPITALIZE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_cost_line_amount");
        costLine(id, 1, "FREIGHT", "0.00", "UNITS", "CAPITALIZE");
        costLine(id, 2, "FREIGHT", "1.00", "WEIGHT", "PERIOD");
    }

    @Test
    @DisplayName("cost line positions are unique within a consignment")
    void costPositionsAreUnique() {
        UUID id = draft();
        costLine(id, 1, "FREIGHT", "1.00", "UNITS", "CAPITALIZE");

        assertThatThrownBy(() -> costLine(id, 1, "DUTY", "0.00", "DIRECT", "CAPITALIZE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_cost_line_position");
    }

    @Test
    @DisplayName("a typed share is one amount for one line of one cost, never negative, and never twice")
    void sharesAreOnePerLinePerCost() {
        UUID id = draft();
        UUID line = normalLine(id, 1, "10.00");
        UUID cost = costLine(id, 1, "DUTY", "5.00", "DIRECT", "CAPITALIZE");
        jdbc.update("INSERT INTO cost_line_share (cost_line_id, consignment_line_id, amount) VALUES (?, ?, 5.00)", cost, line);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cost_line_share (cost_line_id, consignment_line_id, amount) VALUES (?, ?, 1.00)", cost, line))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cost_line_share_pkey");
        UUID other = normalLine(id, 2, "10.00");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cost_line_share (cost_line_id, consignment_line_id, amount) VALUES (?, ?, -1.00)", cost, other))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_cost_line_share_amount");
    }

    // ------------------------------------------------------ orders and removal

    @Test
    @DisplayName("an order's consignment, when set, is a real one")
    void anOrderPointsAtARealConsignment() {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_order (id, supplier_id, placed_on, status) VALUES (?, ?, current_date, 'OPEN')",
                order, supplier);

        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_order SET consignment_id = ? WHERE id = ?", UUID.randomUUID(), order))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_purchase_order_consignment");
        jdbc.update("UPDATE purchase_order SET consignment_id = ? WHERE id = ?", draft(), order);
    }

    @Test
    @DisplayName("removing a draft removes its lines, cost lines and typed shares with it, and an order still pointing at it blocks the removal")
    void removingADraftTakesItsPartsAndNeverStrandsAnOrder() {
        UUID id = draft();
        UUID line = normalLine(id, 1, "10.00");
        UUID cost = costLine(id, 1, "DUTY", "5.00", "DIRECT", "CAPITALIZE");
        jdbc.update("INSERT INTO cost_line_share (cost_line_id, consignment_line_id, amount) VALUES (?, ?, 5.00)", cost, line);
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_order (id, supplier_id, placed_on, status, consignment_id) VALUES (?, ?, current_date, 'OPEN', ?)",
                order, supplier, id);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM consignment WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_purchase_order_consignment");

        jdbc.update("UPDATE purchase_order SET consignment_id = NULL WHERE id = ?", order);
        jdbc.update("DELETE FROM consignment WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM consignment_line WHERE consignment_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_line WHERE consignment_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_line_share WHERE cost_line_id = ?", Long.class, cost)).isZero();
    }
}
