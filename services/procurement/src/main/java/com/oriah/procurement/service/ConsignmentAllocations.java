package com.oriah.procurement.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import com.oriah.procurement.allocation.ConsignmentCosting.LineBasis;
import com.oriah.procurement.allocation.ConsignmentCosting.LineView;
import com.oriah.procurement.allocation.ConsignmentCosting.Outcome;
import com.oriah.procurement.allocation.ConsignmentCosting.SliceView;
import com.oriah.procurement.allocation.LandedCostAllocator.CostInput;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostLine;
import com.oriah.procurement.model.CostTreatment;
import com.oriah.procurement.model.CostType;

/**
 * The allocation that was recorded when a consignment was received, and every later correction of it.
 *
 * <p>Revision 1 is the receipt. It keeps every figure the allocator was given (in whole ngwee, as JSON), the result
 * line by line, the unit-cost slices and what each cost put on each line. The record is append-only (the database
 * refuses any change to it): a correction is a LATER revision with a reason, never an edit, and it is worked out from
 * the revision before it (its recorded inputs), never from the consignment's draft rows, which are frozen as they
 * were. Reading back answers with the latest revision unless one is asked for.
 *
 * <p>Plain SQL, not entities: these rows are written once and read as a whole, and joining the service's transaction
 * (so a receipt or correction that fails leaves no record) is what matters.
 */
@Component
public class ConsignmentAllocations {

    /** The recorded inputs' own version. 2 adds each cost's {@code incurredOn} and {@code note}; 1 is still read. */
    static final int SNAPSHOT_SCHEMA_VERSION = 2;

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
                        long amountNgwee, List<Long> typedSharesNgwee, String typedBy, Instant typedAt,
                        LocalDate incurredOn, String note) {
    }

    /**
     * A revision as it was recorded: the goods it was worked out from, the costs, and whether the consignment was
     * still an estimate as of it. {@code costs} are detached cost lines (nothing about the consignment is touched),
     * with the typist as recorded.
     */
    public record RevisionState(int revision, String reason, boolean estimated, Instant createdAt, String createdBy,
                                List<LineBasis> lines, List<CostLine> costs) {
    }

    /** One revision in the list of revisions, with the lines whose cost it changed. */
    public record RevisionRow(int revision, String reason, String createdBy, Instant createdAt, boolean estimated,
                              BigDecimal goodsTotal, BigDecimal capitalizedCostTotal, BigDecimal periodCostTotal,
                              BigDecimal landedTotal, List<UUID> changedLines) {
    }

    /** Records revision 1: the receipt itself. Part of the receiving transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReceipt(Consignment consignment, Outcome outcome, String createdBy) {
        record(consignment.id(), 1, null, null, consignment.lines().stream().map(LineBasis::of).toList(), consignment.costLines(),
                outcome, createdBy, consignment.receivedAt());
    }

    /**
     * Records a revision: the receipt (1) or a correction (2 and up, with its reason). Every line gets its rows, even one
     * that did not change, so any revision is complete on its own.
     *
     * @param estimated whether the consignment is still an estimate as of it; null for revision 1 (the consignment's own
     *                  frozen flag stands)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID consignmentId, int revisionNumber, String reason, Boolean estimated, List<LineBasis> lines, List<CostLine> costLines,
                       Outcome outcome, String createdBy, Instant createdAt) {
        if (!outcome.ready() || outcome.inputs() == null) {
            throw new IllegalArgumentException("only a consignment that can be costed has an allocation to record");
        }
        Inputs inputs = outcome.inputs();
        List<SnapshotLine> snapshotLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LineBasis line = lines.get(i);
            var input = inputs.lines().get(i);
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
                    cost.treatment().name(), input.amountNgwee(), typed, cost.typedBy(), cost.typedAt(), cost.incurredOn(), cost.note()));
        }
        String snapshot = EventJson.write(new Snapshot(SNAPSHOT_SCHEMA_VERSION, snapshotLines, snapshotCosts));

        UUID revision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO allocation_revision (id, consignment_id, revision, reason, inputs, total_goods, total_capitalized,
                                                 total_period, total_landed, created_at, created_by, is_estimated)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)""",
                revision, consignmentId, revisionNumber, reason, snapshot, outcome.goodsTotal(), outcome.capitalizedCostTotal(),
                outcome.periodCostTotal(), outcome.landedTotal(), Timestamp.from(createdAt), createdBy, estimated);

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

    /** The newest revision number recorded for a consignment, or 0 if none. */
    public int latestRevision(UUID consignmentId) {
        Integer latest = jdbc.queryForObject("SELECT max(revision) FROM allocation_revision WHERE consignment_id = ?", Integer.class, consignmentId);
        return latest == null ? 0 : latest;
    }

    /** The newest revision number of each of these consignments that has one, in one query (for a list). */
    public Map<UUID, Integer> latestRevisions(java.util.Collection<UUID> consignmentIds) {
        Map<UUID, Integer> latest = new LinkedHashMap<>();
        if (consignmentIds.isEmpty()) {
            return latest;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT consignment_id, max(revision) FROM allocation_revision WHERE consignment_id = ANY (?) GROUP BY consignment_id");
            ps.setArray(1, con.createArrayOf("uuid", consignmentIds.toArray()));
            return ps;
        }, rs -> {
            latest.put((UUID) rs.getObject(1), rs.getInt(2));
        });
        return latest;
    }

    /** The recorded allocation of a consignment as the latest revision stands, or empty if none was recorded. */
    public Optional<Recorded> latest(Consignment consignment) {
        int revision = latestRevision(consignment.id());
        return revision == 0 ? Optional.empty() : at(consignment, revision);
    }

    /** One recorded revision, or empty if there is no such revision. */
    public Optional<Recorded> at(Consignment consignment, int revision) {
        List<Map<String, Object>> revisions = jdbc.queryForList("""
                SELECT id, revision, total_goods, total_capitalized, total_period, total_landed
                FROM allocation_revision WHERE consignment_id = ? AND revision = ?""", consignment.id(), revision);
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

        // The costs of a revision are the ones it was worked out from: the receipt's are the consignment's own (frozen)
        // cost lines, a correction's are in its recorded inputs.
        List<CostLine> costLines = stateOf(consignment, revision).costs();
        List<CostView> costs = new ArrayList<>();
        for (CostLine cost : costLines) {
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
        return Optional.of(new Recorded(revision, consignment.receivedAt(), outcome));
    }

    /**
     * What a revision was worked out from: the goods per line, the costs (with their typists) and whether the
     * consignment was still an estimate as of it. A correction is built on this, never on a draft's live rows.
     *
     * <p>The receipt's inputs are the consignment's own rows, frozen at receipt (they carry every field of the cost
     * lines, as typed); a correction's are read back from the inputs it recorded.
     */
    public RevisionState stateOf(Consignment consignment, int revision) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT reason, is_estimated, created_at, created_by, inputs::text AS inputs
                FROM allocation_revision WHERE consignment_id = ? AND revision = ?""", consignment.id(), revision);
        Boolean recorded = (Boolean) row.get("is_estimated");
        Instant createdAt = toInstant(row.get("created_at"));
        String reason = (String) row.get("reason");
        String createdBy = (String) row.get("created_by");
        boolean estimated = recorded != null ? recorded : consignment.isEstimated();

        List<LineBasis> lines = new ArrayList<>();
        List<CostLine> costs = new ArrayList<>();
        if (revision == 1) {
            consignment.lines().forEach(line -> lines.add(LineBasis.of(line)));
            for (CostLine cost : consignment.costLines()) {
                costs.add(consignment.rebuiltCostLine(cost.id(), cost.position(), cost.costType(), cost.amount(), cost.basis(),
                        cost.treatment(), cost.incurredOn(), cost.note(), cost.shares(), cost.typedBy(), cost.typedAt()));
            }
        } else {
            Snapshot snapshot = EventJson.mapper().readValue((String) row.get("inputs"), Snapshot.class);
            List<UUID> lineOrder = new ArrayList<>();
            for (SnapshotLine line : snapshot.lines()) {
                lineOrder.add(line.lineId());
                lines.add(new LineBasis(line.lineId(), line.position(), line.variantId(), line.sku(), line.quantity(),
                        BigDecimal.valueOf(line.goodsNgwee(), 2), line.weightGrams()));
            }
            for (SnapshotCost cost : snapshot.costs()) {
                Map<UUID, BigDecimal> typed = new LinkedHashMap<>();
                if (cost.typedSharesNgwee() != null) {
                    for (int i = 0; i < cost.typedSharesNgwee().size(); i++) {
                        typed.put(lineOrder.get(i), BigDecimal.valueOf(cost.typedSharesNgwee().get(i), 2));
                    }
                }
                costs.add(consignment.rebuiltCostLine(cost.costLineId(), cost.position(), CostType.valueOf(cost.costType()),
                        BigDecimal.valueOf(cost.amountNgwee(), 2), CostBasis.valueOf(cost.basis()),
                        CostTreatment.valueOf(cost.treatment()), cost.incurredOn(), cost.note(), typed, cost.typedBy(),
                        cost.typedAt()));
            }
        }
        return new RevisionState(revision, reason, estimated, createdAt, createdBy, List.copyOf(lines), List.copyOf(costs));
    }

    private static Instant toInstant(Object timestamp) {
        if (timestamp instanceof Timestamp t) {
            return t.toInstant();
        }
        if (timestamp instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        throw new IllegalStateException("not a timestamp: " + timestamp);
    }

    /** Every revision, oldest first, each with the lines whose unit costs or totals it changed from the one before. */
    public List<RevisionRow> revisions(Consignment consignment) {
        List<RevisionRow> rows = new ArrayList<>();
        Map<UUID, String> previous = null;
        for (int number = 1; number <= latestRevision(consignment.id()); number++) {
            Recorded recorded = at(consignment, number).orElseThrow();
            RevisionState state = stateOf(consignment, number);
            Map<UUID, String> figures = figuresOf(recorded.outcome());
            List<UUID> changed = new ArrayList<>();
            if (previous != null) {
                for (LineView line : recorded.outcome().lines()) {
                    boolean estimatedChanged = number > 1 && state.estimated() != rows.get(number - 2).estimated();
                    if (estimatedChanged || !figures.get(line.lineId()).equals(previous.get(line.lineId()))) {
                        changed.add(line.lineId());
                    }
                }
            }
            Outcome o = recorded.outcome();
            rows.add(new RevisionRow(number, state.reason(), state.createdBy(), state.createdAt(), state.estimated(), o.goodsTotal(),
                    o.capitalizedCostTotal(), o.periodCostTotal(), o.landedTotal(), List.copyOf(changed)));
            previous = figures;
        }
        return rows;
    }

    /** What a line comes to, as one comparable string: its totals and its unit costs. */
    public static Map<UUID, String> figuresOf(Outcome outcome) {
        Map<UUID, String> figures = new LinkedHashMap<>();
        for (LineView line : outcome.lines()) {
            StringBuilder s = new StringBuilder(line.goodsTotal().toPlainString()).append('|').append(line.sharedCost().toPlainString())
                    .append('|').append(line.landedTotal().toPlainString());
            line.slices().forEach(slice -> s.append('|').append(slice.quantity()).append('x').append(slice.unitCost().toPlainString()));
            figures.put(line.lineId(), s.toString());
        }
        return figures;
    }

    /** A recorded allocation: which revision stands, when the goods landed, and the figures. */
    public record Recorded(int revision, Instant receivedAt, ConsignmentCosting.Outcome outcome) {
    }
}
