package com.oriah.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;
import com.oriah.order.service.DispatchService.Recorded;
import com.oriah.order.service.DispatchService.Step;

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
                       BigDecimal farePaid, Instant deliveredAt) {
    }

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final DispatchService dispatch;
    private final BoxSwaps swaps;

    public DispatchReadService(JdbcTemplate jdbc, OrderRepository orders, DispatchService dispatch, BoxSwaps swaps) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.dispatch = dispatch;
        this.swaps = swaps;
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
     * The packing slip of one order.
     *
     * @throws ResourceNotFoundException 404, no such order
     * @throws SalesProblem 409 {@code order-not-paid}, before anything personal is read, for an order that is unpaid or was cancelled
     */
    @Transactional(readOnly = true)
    public Slip slip(UUID orderId, Viewer viewer) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        if (order.paidAt() == null || List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_SUBMITTED, OrderStatus.EXPIRED, OrderStatus.CANCELLED).contains(order.status())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-paid", "this order is " + order.status() + ", not paid: there is nothing to pack or send yet");
        }
        log.info("Packing slip of order {} read by {}", orderId, viewer.actor());
        Map<Step, Recorded> steps = dispatch.stepsOf(orderId);
        Recorded sent = steps.get(Step.DISPATCHED);
        SlipDelivery delivery = null;
        if (!order.isSelfCollect() && order.hasDeliveryDetails() && (order.status() != OrderStatus.DELIVERED || viewer.mayViewDeliveredPerson())) {
            delivery = new SlipDelivery(order.deliveryOption(), order.deliveryOptionName(), order.deliveryPayer(), order.deliveryTownName(), order.recipientName(),
                    order.recipientPhone(), new Address(order.addressLine1(), order.addressLine2(), order.addressCity(), order.addressState(),
                            order.addressPostalCode(), order.addressCountry()));
        }
        List<SlipLine> lines = order.items().stream()
                .sorted(Comparator.comparing(OrderItem::title).thenComparing(OrderItem::id))
                .map(i -> new SlipLine(i.id(), i.title(), i.quantity(), i.boxVariantId() == null ? null : new Box(i.boxVariantId(), null)))
                .toList();
        return new Slip(order.id(), order.receiptNumber(), order.status(), DispatchService.stage(order.status()), order.paidAt(), order.isSelfCollect(),
                FareRule.due(order.deliveryOption(), order.deliveryPayer()), swaps.isInFlight(orderId), delivery, lines,
                at(steps.get(Step.PACKED)), at(sent), sent == null ? null : sent.reference(), viewer.mayViewCost() && sent != null ? sent.farePaid() : null,
                at(steps.get(Step.DELIVERED)));
    }

    private static Instant at(Recorded step) {
        return step == null ? null : step.occurredAt();
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
