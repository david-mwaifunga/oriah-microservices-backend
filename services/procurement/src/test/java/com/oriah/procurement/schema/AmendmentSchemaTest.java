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
 * V7: a received consignment's cost can be corrected by a NEW revision of its allocation. Nothing already recorded
 * is touched (the allocation tables stay append-only and V2-V6 are not edited); V7 only makes room for what a
 * revision needs that revision 1 did not.
 *
 * <ul>
 *   <li>A revision says whether the consignment is still an ESTIMATE as of it. Revision 1 is the receipt and leaves
 *       it unsaid (the consignment's own flag stands: it is frozen, and may not be cleared); every later revision
 *       must say.</li>
 *   <li>What each cost put on each line is recorded per revision, and a revision may carry costs that were never
 *       cost lines of the draft (a late duty, a corrected courier charge), so the share rows no longer need a
 *       cost line behind them: the revision's own recorded inputs are the record of which costs it had.</li>
 *   <li>A line's goods are above zero in every revision, so a correction can never make goods free.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class AmendmentSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void wipe() {
        ProcurementTestData.wipe(jdbc);
    }

    /** A received consignment with one line and its revision 1, the way receiving records it. */
    private record Received(UUID consignment, UUID line, UUID revision1) {
    }

    private Received received() {
        UUID supplier = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, name, country) VALUES (?, 'Amendment Schema Supplier', 'AE')", supplier);
        UUID consignment = UUID.randomUUID();
        jdbc.update("INSERT INTO consignment (id, status, is_opening, is_estimated) VALUES (?, 'DRAFT', false, false)", consignment);
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_order (id, supplier_id, placed_on, status, consignment_id) VALUES (?, ?, current_date, 'OPEN', ?)",
                order, supplier, consignment);
        UUID orderLine = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order_line (id, purchase_order_id, position, variant_id, quantity, total_paid, sku, variant_title, kind)
                VALUES (?, ?, 1, ?, 7, 100.00, 'SKU-1', 'A ring', 'SELLABLE')""", orderLine, order, UUID.randomUUID());
        UUID line = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO consignment_line (id, consignment_id, is_opening, position, supplier_id, purchase_order_id, purchase_order_line_id,
                                              variant_id, sku, variant_title, kind, quantity, goods_total)
                VALUES (?, ?, false, 1, ?, ?, ?, ?, 'SKU-1', 'A ring', 'SELLABLE', 7, 100.00)""",
                line, consignment, supplier, order, orderLine, UUID.randomUUID());
        jdbc.update("UPDATE consignment SET status = 'RECEIVED', received_at = now() WHERE id = ?", consignment);
        UUID revision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, inputs, total_goods, total_capitalized, total_period, total_landed)
                VALUES (?, ?, 1, '{"schemaVersion":1}'::jsonb, 100.00, 0.00, 0.00, 100.00)""", revision, consignment);
        return new Received(consignment, line, revision);
    }

    @Test
    @DisplayName("revision 1 may leave the estimate unsaid; every later revision must say whether it is still an estimate")
    void aRevisionSaysWhetherItIsEstimated() {
        Received r = received();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, reason, inputs, total_goods, total_capitalized, total_period, total_landed)
                VALUES (?, ?, 2, 'courier re-billed', '{}'::jsonb, 100.00, 5.00, 0.00, 105.00)""", UUID.randomUUID(), r.consignment()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_allocation_revision_estimated");
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, reason, inputs, total_goods, total_capitalized, total_period, total_landed, is_estimated)
                VALUES (?, ?, 2, 'courier re-billed', '{}'::jsonb, 100.00, 5.00, 0.00, 105.00, false)""", UUID.randomUUID(), r.consignment());
        assertThat(jdbc.queryForObject("SELECT is_estimated FROM allocation_revision WHERE id = ?", Boolean.class, r.revision1()))
                .as("revision 1 says nothing: the consignment's own flag stands").isNull();
    }

    @Test
    @DisplayName("a revision may share a cost that was never a cost line of the draft: the share row no longer needs one")
    void aCorrectedCostNeedNotBeADraftCostLine() {
        Received r = received();
        UUID revision2 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, reason, inputs, total_goods, total_capitalized, total_period, total_landed, is_estimated)
                VALUES (?, ?, 2, 'a late duty', '{}'::jsonb, 100.00, 5.00, 0.00, 105.00, false)""", revision2, r.consignment());

        jdbc.update("INSERT INTO allocation_cost_share (revision_id, cost_line_id, consignment_line_id, amount) VALUES (?, ?, ?, 5.00)",
                revision2, UUID.randomUUID(), r.line());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM allocation_cost_share WHERE revision_id = ?", Integer.class, revision2)).isEqualTo(1);
    }

    @Test
    @DisplayName("the allocation stays append-only after V7: a share, once recorded, cannot be edited or deleted")
    void stillAppendOnly() {
        Received r = received();
        UUID revision2 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, reason, inputs, total_goods, total_capitalized, total_period, total_landed, is_estimated)
                VALUES (?, ?, 2, 'a late duty', '{}'::jsonb, 100.00, 5.00, 0.00, 105.00, false)""", revision2, r.consignment());
        jdbc.update("INSERT INTO allocation_cost_share (revision_id, cost_line_id, consignment_line_id, amount) VALUES (?, ?, ?, 5.00)",
                revision2, UUID.randomUUID(), r.line());

        assertThatThrownBy(() -> jdbc.update("UPDATE allocation_cost_share SET amount = 6.00")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM allocation_revision WHERE id = ?", revision2)).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a line's goods are above zero in every revision")
    void goodsAreAboveZero() {
        Received r = received();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO allocation_line (id, revision_id, consignment_line_id, goods_total, shared_cost, landed_total) VALUES (?, ?, ?, 0.00, 5.00, 5.00)""",
                UUID.randomUUID(), r.revision1(), r.line()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_allocation_line_goods");
    }
}
