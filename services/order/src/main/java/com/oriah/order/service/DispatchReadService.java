package com.oriah.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.client.CatalogLookupClient;

import feign.FeignException;

import lombok.extern.slf4j.Slf4j;

/**
 * What the packer reads (Phase 8): the work list and the packing slip. The packer needs who the parcel is for, where it goes and what is in it, and must
 * read NO MONEY (Q29): not the charge, the total, a discount, a unit price, the waiver, the shopper's login, nor who did what. The courier fare is shown only
 * to a caller who may see cost. A slip is served only for an order that is paid (and cancelled or unpaid ones are refused before anything personal is read),
 * and once the order is delivered the person's name, phone and address are withheld, except to an order administrator.
 */
@Service
@Slf4j
public class DispatchReadService {

    /** What may be asked of a stage. */
    private static final Map<String, OrderStatus> STAGES = Map.of("TO_PACK", OrderStatus.PAID, "PACKED", OrderStatus.FULFILLED, "ON_THE_WAY", OrderStatus.SHIPPED);
    private static final int LIST_LIMIT = 100;

    /** Who is reading, and what they may see beyond the slip. */
    public record Viewer(String actor, boolean mayViewCost, boolean mayViewDeliveredPerson) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(UUID orderId, String receiptNumber, OrderStatus status, String stage, String optionName, String payer, String townName,
                      String recipientName, int pieces, Instant paidAt, Instant packedAt, Instant dispatchedAt, boolean packable) {
    }

    public record Queue(String stage, int count, List<Row> orders) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Box(UUID variantId, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SlipLine(UUID lineId, String title, int quantity, Box box) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(String line1, String line2, String city, String state, String postalCode, String country) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SlipDelivery(String optionCode, String optionName, String payer, String townName, String recipientName, String recipientPhone, Address address) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Slip(UUID orderId, String receiptNumber, OrderStatus status, String stage, Instant paidAt, boolean selfCollect, boolean fareDue,
                       boolean boxSwapInProgress, SlipDelivery delivery, List<SlipLine> lines, Instant packedAt, Instant dispatchedAt, String reference,
                       BigDecimal farePaid, Instant deliveredAt, boolean boxNamesAvailable, String boxNamesProblem) {

        Slip withNames(List<SlipLine> named, boolean available, String problem) {
            return new Slip(orderId, receiptNumber, status, stage, paidAt, selfCollect, fareDue, boxSwapInProgress, delivery, named, packedAt, dispatchedAt, reference,
                    farePaid, deliveredAt, available, problem);
        }
    }

    private final JdbcTemplate jdbc;
    private final DispatchSlips slips;
    private final CatalogLookupClient lookup;

    public DispatchReadService(JdbcTemplate jdbc, DispatchSlips slips, CatalogLookupClient lookup) {
        this.jdbc = jdbc;
        this.slips = slips;
        this.lookup = lookup;
    }

    /**
     * The work list: paid orders by stage, oldest payment first, at most 100.
     *
     * @param stage TO_PACK (the default), PACKED or ON_THE_WAY
     * @throws IllegalArgumentException (400) any other stage
     */
    @Transactional(readOnly = true)
    public Queue queue(String stage) {
        String wanted = stage == null || stage.isBlank() ? "TO_PACK" : stage.trim();
        OrderStatus status = STAGES.get(wanted);
        if (status == null) {
            throw new IllegalArgumentException("stage must be TO_PACK, PACKED or ON_THE_WAY");
        }
        List<Row> rows = jdbc.query("""
                SELECT o.id, o.receipt_number, o.status, o.delivery_option, o.delivery_option_name, o.delivery_payer, o.delivery_town_name, o.recipient_name,
                       o.address_line1, o.paid_at,
                       coalesce((SELECT sum(i.quantity) FROM order_items i WHERE i.order_id = o.id), 0) AS pieces,
                       (SELECT s.occurred_at FROM order_dispatch_step s WHERE s.order_id = o.id AND s.step = 'PACKED') AS packed_at,
                       (SELECT s.occurred_at FROM order_dispatch_step s WHERE s.order_id = o.id AND s.step = 'DISPATCHED') AS dispatched_at
                  FROM orders o
                 WHERE o.status = ?
                 ORDER BY o.paid_at, o.id
                 LIMIT ?""", (rs, n) -> {
            boolean pickup = "SELF_COLLECT".equals(rs.getString("delivery_option"));
            String recipient = rs.getString("recipient_name");
            return new Row(rs.getObject("id", UUID.class), rs.getString("receipt_number"), OrderStatus.valueOf(rs.getString("status")), DispatchService.stage(status),
                    rs.getString("delivery_option_name"), rs.getString("delivery_payer"), rs.getString("delivery_town_name"), recipient,
                    rs.getInt("pieces"), instant(rs.getTimestamp("paid_at")), instant(rs.getTimestamp("packed_at")), instant(rs.getTimestamp("dispatched_at")),
                    pickup || (recipient != null && rs.getString("address_line1") != null));
        }, status.name(), LIST_LIMIT);
        return new Queue(wanted, rows.size(), rows);
    }

    /**
     * The packing slip of one order: read in a short transaction ({@link DispatchSlips}), then the boxes are NAMED by catalog, outside any transaction (no
     * connection is held while another service answers). A name is a courtesy: whatever catalog does, the slip answers 200.
     *
     * @throws com.oriah.observability.ResourceNotFoundException 404, no such order
     * @throws com.oriah.order.exception.SalesProblem 409 {@code order-not-paid}, before anything personal is read, for an order that is unpaid or was cancelled
     */
    public Slip slip(UUID orderId, Viewer viewer) {
        Slip draft = slips.read(orderId, viewer);
        List<UUID> boxes = draft.lines().stream().filter(l -> l.box() != null).map(l -> l.box().variantId()).distinct().toList();
        if (boxes.isEmpty()) {
            return draft.withNames(draft.lines(), true, null);
        }
        try {
            CatalogLookupClient.Result answer = lookup.lookup(new CatalogLookupClient.Lookup(boxes));
            Map<UUID, String> names = new HashMap<>();
            if (answer != null && answer.results() != null) {
                answer.results().stream().filter(e -> e.found() && e.productTitle() != null)
                        .forEach(e -> names.put(e.variantId(), e.variantTitle() == null || e.variantTitle().isBlank() ? e.productTitle() : e.productTitle() + " / " + e.variantTitle()));
            }
            List<SlipLine> named = draft.lines().stream().map(l -> l.box() == null ? l
                    : new SlipLine(l.lineId(), l.title(), l.quantity(), new Box(l.box().variantId(), names.get(l.box().variantId())))).toList();
            return draft.withNames(named, true, null);
        } catch (FeignException e) {
            boolean refused = e.status() == 401 || e.status() == 403;
            if (refused) {
                log.error("catalog REFUSED the lookup of box names with {}: a role or a path is wrong (the slip is served without names)", e.status());
            } else {
                log.warn("catalog could not name the boxes of a packing slip (status {}): the slip is served without names", e.status());
            }
            return draft.withNames(draft.lines(), false, refused ? "forbidden" : "unavailable");
        } catch (RuntimeException e) {
            log.warn("catalog could not name the boxes of a packing slip ({}): the slip is served without names", e.toString());
            return draft.withNames(draft.lines(), false, "unavailable");
        }
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
