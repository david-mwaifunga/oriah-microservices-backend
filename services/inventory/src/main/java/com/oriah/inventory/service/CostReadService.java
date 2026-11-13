package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.inventory.ledger.StockReason;

/**
 * What stock cost: the lots, and the ledger of what was done to them. These are the ONLY replies that carry a cost,
 * and they are served only to COST_VIEWER (the controller checks it; the gateway checks it first). Names come from the
 * lot itself (what the receipt said, at the time), so a bookkeeper, who cannot read catalog's admin list, still sees
 * what each lot is.
 */
@Service
public class CostReadService {

    /** One lot: what a slice of a consignment line cost per unit, and how many of those units are still held. */
    public record CostLotRow(UUID id, UUID variantId, String kind, String sku, String productTitle, String variantTitle, UUID consignmentId,
                             String consignmentReference, UUID consignmentLineId, UUID supplierId, String supplierName, Instant receivedAt,
                             int slicePosition, int revision, int qtyReceived, int qtyRemaining, BigDecimal unitCost, String currency,
                             boolean estimated, boolean replaced) {
    }

    /**
     * One row of the ledger: what an action did to one lot. {@code qtyDelta} is negative when units left the lot; the cost
     * released is {@code -qtyDelta x unitCost}. {@code live} is whether it is in force (not reversed by a later re-cut).
     */
    public record MovementRow(long id, UUID actionId, String actionKind, UUID variantId, UUID lotId, String reason, String role, int qtyDelta,
                              BigDecimal unitCost, String currency, boolean estimated, String kind, String costMethod, String costBasis,
                              Long reversesMovementId, Long replacesMovementId, Instant occurredAt, Instant recordedAt, String actor, String note,
                              String sku, String productTitle, String variantTitle, String consignmentReference, boolean live) {
    }

    /** One lot that still holds stock, as the price check sees it. */
    public record HeldLotRow(UUID lotId, BigDecimal unitCost, int qtyRemaining, boolean estimated, int revision, Instant receivedAt,
                             String consignmentReference) {
    }

    /**
     * What one variant's held stock cost: the dearest lot (what the price is compared with), the weighted average (shown beside
     * it, never compared with) and the lots themselves. A variant that holds nothing still has an entry, with no cost.
     */
    public record HeldVariantCost(UUID variantId, int onHand, int heldUnits, BigDecimal highestUnitCost, BigDecimal averageUnitCost,
                                  String currency, boolean estimated, List<HeldLotRow> lots) {
    }

    private final JdbcTemplate jdbc;

    public CostReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param open            only lots that still hold stock
     * @param includeReplaced also lots an amendment replaced (kept for the record; they hold nothing)
     */
    @Transactional(readOnly = true)
    public Page<CostLotRow> lots(UUID variantId, boolean open, boolean includeReplaced, int page, int size) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (variantId != null) {
            where.add("l.variant_id = ?");
            args.add(variantId);
        }
        if (open) {
            where.add("l.qty_remaining > 0");
        }
        if (!includeReplaced) {
            where.add("l.superseded_at IS NULL");
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = jdbc.queryForObject("SELECT count(*) FROM cost_lot l" + clause, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<CostLotRow> rows = jdbc.query("""
                SELECT l.id, l.variant_id, l.kind, l.sku, l.product_title, l.variant_title, l.consignment_id, l.consignment_reference,
                       l.consignment_line_id, l.supplier_id, l.supplier_name, l.received_at, l.slice_position, l.revision, l.qty_received,
                       l.qty_remaining, l.unit_cost, l.currency, l.is_estimated, l.superseded_at
                  FROM cost_lot l""" + clause + " ORDER BY l.variant_id, l.received_at, l.fifo_tiebreak, l.slice_position, l.revision, l.id LIMIT ? OFFSET ?",
                this::lot, args.toArray());
        return new PageImpl<>(rows, PageRequest.of(page, size), total);
    }

    /**
     * What the stock these variants still hold cost, one answer per DISTINCT id, in the order first asked. One that was never
     * received is answered with nothing held (never left out: a caller that read a missing entry as "no cost" would let a price
     * through unseen).
     *
     * <p>One SQL statement, so every variant's count and lots come from one snapshot: a sale committing between two statements
     * would otherwise show a lot already drained next to the count from before it.
     */
    @Transactional(readOnly = true)
    public List<HeldVariantCost> held(List<UUID> variantIds) {
        List<UUID> ids = variantIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        java.util.Map<UUID, Integer> onHand = new java.util.LinkedHashMap<>();
        java.util.Map<UUID, List<HeldLotRow>> lots = new java.util.LinkedHashMap<>();
        for (UUID id : ids) {
            onHand.put(id, 0);
            lots.put(id, new ArrayList<>());
        }
        jdbc.query(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement("""
                    SELECT r.variant_id, i.on_hand, l.id AS lot_id, l.unit_cost, l.qty_remaining, l.is_estimated, l.revision,
                           l.received_at, l.consignment_reference
                      FROM unnest(?::uuid[]) WITH ORDINALITY AS r(variant_id, ord)
                      LEFT JOIN inventory i ON i.variant_id = r.variant_id
                      LEFT JOIN cost_lot l ON l.variant_id = r.variant_id AND l.qty_remaining > 0 AND l.superseded_at IS NULL
                     ORDER BY r.ord, l.received_at, l.fifo_tiebreak, l.slice_position, l.revision, l.id""");
            ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, (java.sql.ResultSet rs) -> {
            UUID variant = rs.getObject("variant_id", UUID.class);
            int count = rs.getInt("on_hand");
            if (!rs.wasNull()) {
                onHand.put(variant, count);
            }
            UUID lotId = rs.getObject("lot_id", UUID.class);
            if (lotId != null) {
                lots.get(variant).add(new HeldLotRow(lotId, rs.getBigDecimal("unit_cost"), rs.getInt("qty_remaining"),
                        rs.getBoolean("is_estimated"), rs.getInt("revision"), rs.getTimestamp("received_at").toInstant(),
                        rs.getString("consignment_reference")));
            }
        });
        return ids.stream().map(id -> HeldCosts.summarise(id, onHand.get(id), lots.get(id))).toList();
    }

    @Transactional(readOnly = true)
    public Page<MovementRow> movements(UUID variantId, UUID lotId, UUID actionId, String reason, int page, int size) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (variantId != null) {
            where.add("m.variant_id = ?");
            args.add(variantId);
        }
        if (lotId != null) {
            where.add("m.lot_id = ?");
            args.add(lotId);
        }
        if (actionId != null) {
            where.add("m.action_id = ?");
            args.add(actionId);
        }
        if (reason != null) {
            where.add("m.reason = ?");
            args.add(StockReason.fromCode(reason).name());
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = jdbc.queryForObject("SELECT count(*) FROM stock_movement m" + clause, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<MovementRow> rows = jdbc.query("""
                SELECT m.id, m.action_id, a.kind AS action_kind, m.variant_id, m.lot_id, m.reason, m.role, m.qty_delta, m.unit_cost, m.currency,
                       m.is_estimated, m.kind, m.cost_method, m.cost_basis, m.reverses_movement_id, m.replaces_movement_id, m.occurred_at,
                       m.recorded_at, m.actor, a.note, l.sku, l.product_title, l.variant_title, l.consignment_reference,
                       (m.role IN ('ORIGINAL', 'RECUT_REPLACEMENT')
                        AND NOT EXISTS (SELECT 1 FROM stock_movement r WHERE r.role = 'RECUT_REVERSAL' AND r.reverses_movement_id = m.id)) AS live
                  FROM stock_movement m
                  JOIN stock_action a ON a.variant_id = m.variant_id AND a.id = m.action_id
                  JOIN cost_lot l ON l.id = m.lot_id""" + clause + " ORDER BY m.id LIMIT ? OFFSET ?", this::movement, args.toArray());
        return new PageImpl<>(rows, PageRequest.of(page, size), total);
    }

    private CostLotRow lot(ResultSet rs, int n) throws SQLException {
        return new CostLotRow(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("kind"), rs.getString("sku"),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getObject("consignment_id", UUID.class),
                rs.getString("consignment_reference"), rs.getObject("consignment_line_id", UUID.class), rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"), rs.getTimestamp("received_at").toInstant(), rs.getInt("slice_position"), rs.getInt("revision"),
                rs.getInt("qty_received"), rs.getInt("qty_remaining"), rs.getBigDecimal("unit_cost"), rs.getString("currency"),
                rs.getBoolean("is_estimated"), rs.getTimestamp("superseded_at") != null);
    }

    private MovementRow movement(ResultSet rs, int n) throws SQLException {
        return new MovementRow(rs.getLong("id"), rs.getObject("action_id", UUID.class), rs.getString("action_kind"), rs.getObject("variant_id", UUID.class),
                rs.getObject("lot_id", UUID.class), rs.getString("reason"), rs.getString("role"), rs.getInt("qty_delta"), rs.getBigDecimal("unit_cost"),
                rs.getString("currency"), rs.getBoolean("is_estimated"), rs.getString("kind"), rs.getString("cost_method"), rs.getString("cost_basis"),
                (Long) rs.getObject("reverses_movement_id"), (Long) rs.getObject("replaces_movement_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(), rs.getString("actor"), rs.getString("note"), rs.getString("sku"), rs.getString("product_title"),
                rs.getString("variant_title"), rs.getString("consignment_reference"), rs.getBoolean("live"));
    }
}
