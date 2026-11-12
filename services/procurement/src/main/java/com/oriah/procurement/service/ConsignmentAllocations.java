package com.oriah.procurement.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.procurement.allocation.ConsignmentCosting;
import com.oriah.procurement.allocation.ConsignmentCosting.CostView;
import com.oriah.procurement.allocation.ConsignmentCosting.Inputs;
import com.oriah.procurement.allocation.ConsignmentCosting.LineView;
import com.oriah.procurement.allocation.ConsignmentCosting.Outcome;
import com.oriah.procurement.allocation.ConsignmentCosting.SliceView;
import com.oriah.procurement.allocation.LandedCostAllocator.CostInput;
import com.oriah.procurement.allocation.LandedCostAllocator.LineInput;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.model.CostLine;

/**
 * The allocation that was recorded when a consignment was received, and reading it back.
 *
 * <p>Revision 1 is the receipt. It keeps every figure the allocator was given (in whole ngwee,
 * as JSON), the result line by line, the unit-cost slices and what each cost put on each line.
 * The record is append-only (the database refuses any change to it): a correction is a later
 * revision with a reason, never an edit. Reading back always answers with the LATEST revision.
 *
 * <p>Plain SQL, not entities: these rows are written once and read as a whole, and joining the
 * service's transaction (so a receipt that fails leaves no record) is what matters.
 */
@Component
public class ConsignmentAllocations {

    /** The recorded inputs' own version, for the day the shape has to change. */
    static final int SNAPSHOT_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbc;

    public ConsignmentAllocations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the allocator was given, with the ids that say which line and which cost each was. */
    record Snapshot(int schemaVersion, List<SnapshotLine> lines, List<SnapshotCost> costs) {
    }

    record SnapshotLine(UUID lineId, int position, UUID variantId, String sku, int quantity, long goodsNgwee,
                        Long weightGrams) {
    }

    record SnapshotCost(UUID costLineId, int position, String costType, String basis, String treatment,
                        long amountNgwee, List<Long> typedSharesNgwee, String typedBy, Instant typedAt) {
    }

    /** Records revision 1: the receipt itself. Part of the receiving transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReceipt(Consignment consignment, Outcome outcome, String createdBy) {
        if (!outcome.ready() || outcome.inputs() == null) {
            throw new IllegalArgumentException("only a consignment that can be costed has an allocation to record");
        }
        List<ConsignmentLine> lines = consignment.lines();
        List<CostLine> costLines = consignment.costLines();

        Inputs inputs = outcome.inputs();
        List<SnapshotLine> snapshotLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ConsignmentLine line = lines.get(i);
            LineInput input = inputs.lines().get(i);
            snapshotLines.add(new SnapshotLine(line.id(), line.position(), line.variantId(), line.sku(),
                    input.quantity(), input.goodsNgwee(), input.weightGrams()));
        }
        List<SnapshotCost> snapshotCosts = new ArrayList<>();
        for (int i = 0; i < costLines.size(); i++) {
            CostLine cost = costLines.get(i);
            CostInput input = inputs.costs().get(i);
            List<Long> typed = null;
            if (input.typedSharesNgwee() != null) {
                typed = new ArrayList<>();
                for (long share : input.typedSharesNgwee()) {
                    typed.add(share);
                }
            }
            snapshotCosts.add(new SnapshotCost(cost.id(), cost.position(), cost.costType().name(), cost.basis().name(),
                    cost.treatment().name(), input.amountNgwee(), typed, cost.typedBy(), cost.typedAt()));
        }
        String snapshot = EventJson.write(new Snapshot(SNAPSHOT_SCHEMA_VERSION, snapshotLines, snapshotCosts));

        UUID revision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, inputs, total_goods, total_capitalized,
                                                 total_period, total_landed, created_by)
                VALUES (?, ?, 1, CAST(? AS jsonb), ?, ?, ?, ?, ?)""",
                revision, consignment.id(), snapshot, outcome.goodsTotal(), outcome.capitalizedCostTotal(),
                outcome.periodCostTotal(), outcome.landedTotal(), createdBy);

        for (LineView line : outcome.lines()) {
            UUID allocationLine = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO allocation_line (id, revision_id, consignment_line_id, goods_total, shared_cost, landed_total)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    allocationLine, revision, line.lineId(), line.goodsTotal(), line.sharedCost(), line.landedTotal());
            int position = 0;
            for (SliceView slice : line.slices()) {
                jdbc.update("INSERT INTO allocation_slice (allocation_line_id, position, quantity, unit_cost) VALUES (?, ?, ?, ?)",
                        allocationLine, ++position, slice.quantity(), slice.unitCost());
            }
        }
        for (CostView cost : outcome.costs()) {
            for (Map.Entry<UUID, BigDecimal> share : cost.perLine().entrySet()) {
                jdbc.update("""
                        INSERT INTO allocation_cost_share (revision_id, cost_line_id, consignment_line_id, amount)
                        VALUES (?, ?, ?, ?)""", revision, cost.costLineId(), share.getKey(), share.getValue());
            }
        }
    }

    /** The recorded allocation of a consignment as the latest revision stands, or empty if none was recorded. */
    public Optional<Recorded> latest(Consignment consignment) {
        List<Map<String, Object>> revisions = jdbc.queryForList("""
                SELECT id, revision, total_goods, total_capitalized, total_period, total_landed
                FROM allocation_revision WHERE consignment_id = ? ORDER BY revision DESC LIMIT 1""", consignment.id());
        if (revisions.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> revisionRow = revisions.get(0);
        UUID revisionId = (UUID) revisionRow.get("id");

        Map<UUID, List<SliceView>> slicesByLine = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.consignment_line_id, s.quantity, s.unit_cost
                FROM allocation_line a JOIN allocation_slice s ON s.allocation_line_id = a.id
                WHERE a.revision_id = ? ORDER BY a.consignment_line_id, s.position""",
                rs -> {
                    slicesByLine.computeIfAbsent((UUID) rs.getObject(1), k -> new ArrayList<>())
                            .add(new SliceView(rs.getInt(2), rs.getBigDecimal(3)));
                }, revisionId);

        Map<UUID, BigDecimal[]> totalsByLine = new LinkedHashMap<>();
        jdbc.query("SELECT consignment_line_id, goods_total, shared_cost, landed_total FROM allocation_line WHERE revision_id = ?",
                rs -> {
                    totalsByLine.put((UUID) rs.getObject(1),
                            new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)});
                }, revisionId);

        Map<UUID, Map<UUID, BigDecimal>> sharesByCost = new LinkedHashMap<>();
        jdbc.query("SELECT cost_line_id, consignment_line_id, amount FROM allocation_cost_share WHERE revision_id = ?",
                rs -> {
                    sharesByCost.computeIfAbsent((UUID) rs.getObject(1), k -> new LinkedHashMap<>())
                            .put((UUID) rs.getObject(2), rs.getBigDecimal(3));
                }, revisionId);

        List<LineView> lines = new ArrayList<>();
        for (ConsignmentLine line : consignment.lines()) {
            BigDecimal[] totals = totalsByLine.get(line.id());
            if (totals == null) {
                throw new IllegalStateException("the recorded allocation has no line " + line.id());
            }
            lines.add(new LineView(line.id(), line.position(), line.sku(), line.quantity(), totals[0], totals[1],
                    totals[2], List.copyOf(slicesByLine.getOrDefault(line.id(), List.of()))));
        }
        List<CostView> costs = new ArrayList<>();
        for (CostLine cost : consignment.costLines()) {
            Map<UUID, BigDecimal> recorded = sharesByCost.getOrDefault(cost.id(), Map.of());
            // In line order, whatever order the database handed them back in.
            Map<UUID, BigDecimal> perLine = new LinkedHashMap<>();
            if (!recorded.isEmpty()) {
                for (ConsignmentLine line : consignment.lines()) {
                    perLine.put(line.id(), recorded.get(line.id()));
                }
            }
            costs.add(new CostView(cost.id(), cost.position(), cost.costType(), cost.basis(), cost.treatment(),
                    cost.amount(), !cost.shares().isEmpty(), cost.typedBy(), cost.typedAt(), perLine));
        }

        Outcome outcome = new Outcome(true, List.of(), lines, costs, (BigDecimal) revisionRow.get("total_goods"),
                (BigDecimal) revisionRow.get("total_capitalized"), (BigDecimal) revisionRow.get("total_period"),
                (BigDecimal) revisionRow.get("total_landed"), null);
        return Optional.of(new Recorded(((Number) revisionRow.get("revision")).intValue(), consignment.receivedAt(), outcome));
    }

    /** A recorded allocation: which revision stands, when the goods landed, and the figures. */
    public record Recorded(int revision, Instant receivedAt, ConsignmentCosting.Outcome outcome) {
    }
}
