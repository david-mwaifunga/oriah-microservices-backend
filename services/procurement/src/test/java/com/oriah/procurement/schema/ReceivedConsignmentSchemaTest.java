package com.oriah.procurement.schema;

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

import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the database keeps true about a RECEIVED consignment and its allocation, with raw SQL
 * and no entity.
 *
 * <p>Once the goods have landed and the unit costs are worked out, those costs are the books:
 * profit is computed from them for years. So the database itself refuses to change a received
 * consignment, its lines, its cost lines or what was typed for them, and refuses to change or
 * delete a single row of the allocation that was recorded. A correction is a NEW revision
 * (Phase 5), never an edit. The refusals are pinned by message so a refusal is known to be the
 * intended one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ReceivedConsignmentSchemaTest {

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
        jdbc.update("INSERT INTO suppliers (id, name, country) VALUES (?, 'Receipt Schema Supplier', 'AE')", supplier);
    }

    // ------------------------------------------------------------ fixtures

    /** A draft with one line, one cost line and one typed share: everything that freezes on receipt. */
    private record Draft(UUID consignment, UUID line, UUID cost) {
    }

    private Draft draft() {
        UUID consignment = UUID.randomUUID();
        jdbc.update("INSERT INTO consignment (id, status, is_opening, is_estimated) VALUES (?, 'DRAFT', false, false)",
                consignment);
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_order (id, supplier_id, placed_on, status, consignment_id) "
                + "VALUES (?, ?, current_date, 'OPEN', ?)", order, supplier, consignment);
        UUID orderLine = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order_line (id, purchase_order_id, position, variant_id, quantity, total_paid,
                                                 sku, variant_title, kind)
                VALUES (?, ?, 1, ?, 7, 100.00, 'SKU-1', 'A ring', 'SELLABLE')""", orderLine, order, UUID.randomUUID());
        UUID line = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO consignment_line (id, consignment_id, is_opening, position, supplier_id, purchase_order_id,
                                              purchase_order_line_id, variant_id, sku, variant_title, kind, quantity, goods_total)
                VALUES (?, ?, false, 1, ?, ?, ?, ?, 'SKU-1', 'A ring', 'SELLABLE', 7, 100.00)""",
                line, consignment, supplier, order, orderLine, UUID.randomUUID());
        UUID cost = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cost_line (id, consignment_id, position, cost_type, amount, basis, treatment)
                VALUES (?, ?, 1, 'DUTY', 5.00, 'DIRECT', 'CAPITALIZE')""", cost, consignment);
        jdbc.update("INSERT INTO cost_line_share (cost_line_id, consignment_line_id, amount) VALUES (?, ?, 5.00)", cost, line);
        return new Draft(consignment, line, cost);
    }

    private Draft received() {
        Draft draft = draft();
        jdbc.update("UPDATE consignment SET status = 'RECEIVED', received_at = now() WHERE id = ?", draft.consignment());
        return draft;
    }

    /** A recorded allocation, revision 1, with one line, one slice and one cost share. */
    private UUID revisionWithRows(Draft draft) {
        UUID revision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, inputs, total_goods, total_capitalized,
                                                 total_period, total_landed)
                VALUES (?, ?, 1, '{"schemaVersion":1}'::jsonb, 100.00, 5.00, 0.00, 105.00)""", revision, draft.consignment());
        UUID allocationLine = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO allocation_line (id, revision_id, consignment_line_id, goods_total, shared_cost, landed_total)
                VALUES (?, ?, ?, 100.00, 5.00, 105.00)""", allocationLine, revision, draft.line());
        jdbc.update("INSERT INTO allocation_slice (allocation_line_id, position, quantity, unit_cost) VALUES (?, 1, 7, 15.00)",
                allocationLine);
        jdbc.update("""
                INSERT INTO allocation_cost_share (revision_id, cost_line_id, consignment_line_id, amount)
                VALUES (?, ?, ?, 5.00)""", revision, draft.cost(), draft.line());
        return revision;
    }

    // ---------------------------------------------------------- freezing

    @Test
    @DisplayName("a draft is changed freely: its header, lines, costs and shares")
    void aDraftChangesFreely() {
        Draft draft = draft();

        jdbc.update("UPDATE consignment SET note = 'still a draft' WHERE id = ?", draft.consignment());
        jdbc.update("UPDATE consignment_line SET chargeable_weight_grams = 500 WHERE id = ?", draft.line());
        jdbc.update("UPDATE cost_line SET position = 1 WHERE id = ?", draft.cost());
        jdbc.update("UPDATE cost_line_share SET amount = 5.00 WHERE cost_line_id = ?", draft.cost());
        jdbc.update("DELETE FROM cost_line_share WHERE cost_line_id = ?", draft.cost());
    }

    @Test
    @DisplayName("a received consignment cannot be changed or deleted")
    void aReceivedConsignmentIsFrozen() {
        Draft received = received();

        assertThatThrownBy(() -> jdbc.update("UPDATE consignment SET note = 'edited' WHERE id = ?", received.consignment()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment SET is_estimated = true WHERE id = ?", received.consignment()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM consignment WHERE id = ?", received.consignment()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
    }

    @Test
    @DisplayName("a received consignment's lines cannot be added to, changed or deleted")
    void receivedLinesAreFrozen() {
        Draft received = received();

        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET quantity = 8 WHERE id = ?", received.line()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("UPDATE consignment_line SET chargeable_weight_grams = 1 WHERE id = ?", received.line()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM consignment_line WHERE id = ?", received.line()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO consignment_line (id, consignment_id, is_opening, position, supplier_id, purchase_order_id,
                                              purchase_order_line_id, variant_id, sku, variant_title, kind, quantity, goods_total)
                SELECT ?, consignment_id, false, 2, supplier_id, purchase_order_id, purchase_order_line_id, variant_id,
                       sku, variant_title, kind, quantity, goods_total FROM consignment_line WHERE id = ?""",
                UUID.randomUUID(), received.line()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a received consignment's cost lines and typed shares cannot be added to, changed or deleted")
    void receivedCostsAreFrozen() {
        Draft received = received();

        assertThatThrownBy(() -> jdbc.update("UPDATE cost_line SET amount = 9.00 WHERE id = ?", received.cost()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM cost_line WHERE id = ?", received.cost()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cost_line (id, consignment_id, position, cost_type, amount, basis, treatment)
                VALUES (?, ?, 2, 'FREIGHT', 1.00, 'UNITS', 'CAPITALIZE')""", UUID.randomUUID(), received.consignment()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("UPDATE cost_line_share SET amount = 4.00 WHERE cost_line_id = ?", received.cost()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM cost_line_share WHERE cost_line_id = ?", received.cost()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cost_line_share (cost_line_id, consignment_line_id, amount) VALUES (?, ?, 1.00)",
                received.cost(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("receiving itself is allowed: a draft becomes RECEIVED once, and then nothing about it changes")
    void receivingIsTheLastChange() {
        Draft draft = draft();

        jdbc.update("UPDATE consignment SET status = 'RECEIVED', received_at = now() WHERE id = ?", draft.consignment());

        assertThatThrownBy(() -> jdbc.update("UPDATE consignment SET status = 'DRAFT', received_at = NULL WHERE id = ?",
                draft.consignment()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("received consignment is frozen");
    }

    // ------------------------------------------------------ the allocation

    @Test
    @DisplayName("a recorded allocation has a revision number that counts up from 1, once per consignment")
    void revisionsAreNumbered() {
        Draft draft = draft();
        revisionWithRows(draft);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, inputs, total_goods, total_capitalized,
                                                 total_period, total_landed)
                VALUES (?, ?, 1, '{}'::jsonb, 1.00, 0.00, 0.00, 1.00)""", UUID.randomUUID(), draft.consignment()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_allocation_revision_number");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, inputs, total_goods, total_capitalized,
                                                 total_period, total_landed)
                VALUES (?, ?, 0, '{}'::jsonb, 1.00, 0.00, 0.00, 1.00)""", UUID.randomUUID(), draft.consignment()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_allocation_revision_number");
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, reason, inputs, total_goods,
                                                 total_capitalized, total_period, total_landed)
                VALUES (?, ?, 2, 'courier re-billed', '{}'::jsonb, 1.00, 0.00, 0.00, 1.00)""", UUID.randomUUID(), draft.consignment());
    }

    @Test
    @DisplayName("a slice has at least one unit and a cost of zero or more; a share is not negative; one row per line per revision")
    void allocationAmountsAreSane() {
        Draft draft = draft();
        UUID revision = revisionWithRows(draft);
        UUID allocationLine = jdbc.queryForObject("SELECT id FROM allocation_line WHERE revision_id = ?", UUID.class, revision);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO allocation_slice (allocation_line_id, position, quantity, unit_cost) VALUES (?, 2, 0, 1.00)",
                allocationLine))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_allocation_slice_quantity");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO allocation_slice (allocation_line_id, position, quantity, unit_cost) VALUES (?, 2, 1, -1.00)",
                allocationLine))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_allocation_slice_cost");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO allocation_line (id, revision_id, consignment_line_id, goods_total, shared_cost, landed_total)
                VALUES (?, ?, ?, 1.00, 0.00, 1.00)""", UUID.randomUUID(), revision, draft.line()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_allocation_line_revision_line");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO allocation_cost_share (revision_id, cost_line_id, consignment_line_id, amount) VALUES (?, ?, ?, -1.00)",
                revision, draft.cost(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a recorded allocation is append-only: not one row of it can be changed or deleted")
    void theAllocationIsAppendOnly() {
        Draft draft = draft();
        UUID revision = revisionWithRows(draft);
        UUID allocationLine = jdbc.queryForObject("SELECT id FROM allocation_line WHERE revision_id = ?", UUID.class, revision);

        assertThatThrownBy(() -> jdbc.update("UPDATE allocation_revision SET total_landed = 1.00 WHERE id = ?", revision))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM allocation_revision WHERE id = ?", revision))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE allocation_line SET landed_total = 1.00 WHERE id = ?", allocationLine))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM allocation_line WHERE id = ?", allocationLine))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE allocation_slice SET unit_cost = 1.00 WHERE allocation_line_id = ?", allocationLine))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM allocation_slice WHERE allocation_line_id = ?", allocationLine))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE allocation_cost_share SET amount = 1.00 WHERE revision_id = ?", revision))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM allocation_cost_share WHERE revision_id = ?", revision))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM allocation_slice", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a consignment that has an allocation cannot be deleted, even before anyone marks it received")
    void anAllocatedConsignmentIsNotDeletable() {
        Draft draft = draft();
        revisionWithRows(draft);

        // Cascading the delete would have to remove append-only rows, which the database refuses.
        jdbc.update("UPDATE purchase_order SET consignment_id = NULL WHERE consignment_id = ?", draft.consignment());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM consignment WHERE id = ?", draft.consignment()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("the recorded inputs are JSON, kept so the result can be reproduced from them")
    void inputsAreJson() {
        Draft draft = draft();
        UUID revision = revisionWithRows(draft);

        String schemaVersion = jdbc.queryForObject("SELECT inputs ->> 'schemaVersion' FROM allocation_revision WHERE id = ?",
                String.class, revision);

        assertThat(schemaVersion).isEqualTo("1");
    }
}
