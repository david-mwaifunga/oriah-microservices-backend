package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.model.CostLine;
import com.oriah.procurement.model.CostTreatment;
import com.oriah.procurement.model.PurchaseOrder;
import com.oriah.procurement.model.Supplier;
import com.oriah.procurement.service.ConsignmentAllocations;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A consignment as the admin API shows it: the header, the whole purchase orders in it, the
 * lines copied from them (or typed, for the opening stock), the cost lines, and the totals.
 *
 * <p>The unit costs are NOT here: they are worked out by the allocation preview, and fixed when
 * the goods are received.
 */
@Schema(name = "Consignment")
public record ConsignmentResponse(
        UUID id,
        @Schema(description = "The number a person quotes: C-000045", example = "C-000045") String reference,
        @Schema(description = "DRAFT while being built, RECEIVED once the goods have landed") String status,
        @Schema(description = "The day-one stock typed in from the old book: no orders, no supplier, an estimate")
        boolean opening,
        @Schema(description = "The costs are approximations") boolean estimated,
        @Schema(types = {"string", "null"}) String note,
        @Schema(description = "What the forwarder's invoice says its charge covers: DUTY_PAID or FREIGHT_ONLY. No default",
                types = {"string", "null"}) String chargesBasis,
        @Schema(types = {"string", "null"}) String declarant,
        @Schema(description = "CE6 or CE20", types = {"string", "null"}) String clearanceForm,
        @Schema(types = {"string", "null"}) String zraAssessmentRef,
        @Schema(types = {"boolean", "null"}) Boolean licensedAgentUsed,
        @Schema(description = "When the goods landed; null while a draft", types = {"string", "null"}) Instant receivedAt,
        @Schema(description = "Which revision of the allocation stands (1 is the receipt, 2 and up are corrections); null "
                + "while a draft. When it is above 1 the lines' goods and the cost lines below are as that revision has "
                + "them", types = {"integer", "null"}) Integer revision,
        String currency,
        @Schema(description = "Every line's all-in goods total") BigDecimal goodsTotal,
        @Schema(description = "Cost lines that ride on the goods into the unit costs") BigDecimal capitalizedCostTotal,
        @Schema(description = "Cost lines that are a bill for the month, left out of the unit costs") BigDecimal periodCostTotal,
        List<OrderSummary> purchaseOrders,
        List<Line> lines,
        List<CostLineView> costLines,
        @Schema(description = "Counts changes; send it back on every change") long version,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy) {

    public static final String CURRENCY = "ZMW";

    /** A whole purchase order in this consignment. */
    @Schema(name = "ConsignmentOrder")
    public record OrderSummary(
            UUID id,
            String reference,
            String status,
            PurchaseOrderResponse.SupplierSummary supplier,
            LocalDate placedOn,
            BigDecimal totalPaid,
            @Schema(description = "The order's own version, for detaching or comparing") long version) {
    }

    /** Where a line came from. */
    @Schema(name = "ConsignmentLineOrder")
    public record LineOrder(UUID id, String reference) {
    }

    @Schema(name = "ConsignmentLine")
    public record Line(
            UUID id,
            int position,
            @Schema(description = "Who the goods were bought from; null on an opening line (supplier unknown)",
                    types = {"object", "null"}) PurchaseOrderResponse.SupplierSummary supplier,
            @Schema(description = "The order the line was copied from; null on an opening line",
                    types = {"object", "null"}) LineOrder purchaseOrder,
            UUID variantId,
            String sku,
            @Schema(types = {"string", "null"}) String productTitle,
            String variantTitle,
            @Schema(description = "SELLABLE or PACKAGING") String kind,
            int quantity,
            @Schema(description = "All-in, as the bank app showed it, for the whole line") BigDecimal goodsTotal,
            @Schema(description = "What the courier billed on for the whole line, in whole grams",
                    types = {"integer", "null"}) Long chargeableWeightGrams,
            @Schema(types = {"string", "null"}) String originCountry,
            @Schema(types = {"string", "null"}) String hsCode) {
    }

    @Schema(name = "ConsignmentCostLine")
    public record CostLineView(
            UUID id,
            int position,
            String costType,
            BigDecimal amount,
            @Schema(description = "UNITS, VALUE, WEIGHT or DIRECT") String basis,
            @Schema(description = "CAPITALIZE or PERIOD") String treatment,
            @Schema(types = {"string", "null"}) LocalDate incurredOn,
            @Schema(types = {"string", "null"}) String note,
            @Schema(description = "What a person typed for each line; empty when the split is by the basis")
            List<Share> shares,
            @Schema(description = "Who typed the split (every override records the actor); null when the allocator "
                    + "shares the cost. Kept when someone else saves the list again with that split unchanged",
                    types = {"string", "null"}) String typedBy,
            @Schema(description = "When they typed it", types = {"string", "null"}) Instant typedAt) {
    }

    @Schema(name = "ConsignmentCostShare")
    public record Share(UUID lineId, BigDecimal amount) {
    }

    /**
     * @param orders    the purchase orders in the consignment
     * @param suppliers every supplier they name, deleted ones included
     */
    public static ConsignmentResponse of(Consignment c, List<PurchaseOrder> orders, Map<UUID, Supplier> suppliers) {
        return of(c, orders, suppliers, null, null);
    }

    /**
     * The consignment as its latest recorded revision stands. A received consignment's own rows never change, so
     * after a correction the lines' goods and the cost lines come from the revision's recorded inputs.
     *
     * @param revision the revision that stands (null for a draft)
     * @param state    the recorded inputs of that revision when it is a correction (2 and up); null when the
     *                 consignment's own rows already say it (a draft, or the receipt)
     */
    public static ConsignmentResponse of(Consignment c, List<PurchaseOrder> orders, Map<UUID, Supplier> suppliers,
                                         Integer revision, ConsignmentAllocations.RevisionState state) {
        Map<UUID, PurchaseOrder> orderById = new java.util.HashMap<>();
        orders.forEach(o -> orderById.put(o.id(), o));

        List<OrderSummary> orderViews = orders.stream().map(o -> new OrderSummary(o.id(),
                PurchaseOrderResponse.reference(o.refNo()), o.status().name(), supplierOf(suppliers, o.supplierId()),
                o.placedOn(), o.totalPaid(), o.version() == null ? 0L : o.version())).toList();

        Map<UUID, BigDecimal> goodsNow = new java.util.HashMap<>();
        List<CostLine> costs = c.costLines();
        boolean estimated = c.isEstimated();
        if (state != null) {
            state.lines().forEach(l -> goodsNow.put(l.id(), l.goodsTotal()));
            costs = state.costs();
            estimated = state.estimated();
        }

        List<Line> lineViews = c.lines().stream()
                .map(l -> lineOf(l, goodsNow.getOrDefault(l.id(), l.goodsTotal()), orderById, suppliers)).toList();
        List<CostLineView> costViews = costs.stream().map(ConsignmentResponse::costOf).toList();

        BigDecimal goods = lineViews.stream().map(Line::goodsTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal capitalized = costs.stream().filter(x -> x.treatment() == CostTreatment.CAPITALIZE)
                .map(CostLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal period = costs.stream().filter(x -> x.treatment() == CostTreatment.PERIOD)
                .map(CostLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ConsignmentResponse(c.id(), reference(c.refNo()), c.status().name(), c.isOpening(), estimated,
                c.note(), c.chargesBasis() == null ? null : c.chargesBasis().name(), c.declarant(),
                c.clearanceForm() == null ? null : c.clearanceForm().name(), c.zraAssessmentRef(),
                c.licensedAgentUsed(), c.receivedAt(), revision, CURRENCY, goods, capitalized, period, orderViews, lineViews,
                costViews, c.version() == null ? 0L : c.version(), c.createdAt(), c.updatedAt(), c.createdBy(),
                c.updatedBy());
    }

    private static Line lineOf(ConsignmentLine l, BigDecimal goodsTotal, Map<UUID, PurchaseOrder> orders, Map<UUID, Supplier> suppliers) {
        PurchaseOrder order = l.purchaseOrderId() == null ? null : orders.get(l.purchaseOrderId());
        return new Line(l.id(), l.position(),
                l.supplierId() == null ? null : supplierOf(suppliers, l.supplierId()),
                order == null ? null : new LineOrder(order.id(), PurchaseOrderResponse.reference(order.refNo())),
                l.variantId(), l.sku(), l.productTitle(), l.variantTitle(), l.kind(), l.quantity(), goodsTotal,
                l.chargeableWeightGrams(), l.originCountry(), l.hsCode());
    }

    private static CostLineView costOf(CostLine cost) {
        List<Share> shares = cost.shares().entrySet().stream()
                .map(e -> new Share(e.getKey(), e.getValue())).toList();
        return new CostLineView(cost.id(), cost.position(), cost.costType().name(), cost.amount(),
                cost.basis().name(), cost.treatment().name(), cost.incurredOn(), cost.note(), shares,
                cost.typedBy(), cost.typedAt());
    }

    private static PurchaseOrderResponse.SupplierSummary supplierOf(Map<UUID, Supplier> suppliers, UUID id) {
        Supplier supplier = suppliers.get(id);
        if (supplier == null) {
            throw new IllegalStateException("a consignment names supplier " + id + " that is gone");
        }
        return PurchaseOrderResponse.SupplierSummary.of(supplier);
    }

    /** C-000045: the database's number, padded to six digits (more digits when it needs them). */
    public static String reference(Long refNo) {
        return refNo == null ? null : String.format("C-%06d", refNo);
    }
}
