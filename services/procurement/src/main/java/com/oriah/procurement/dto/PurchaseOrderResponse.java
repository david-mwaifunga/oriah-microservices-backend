package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.oriah.procurement.model.PurchaseOrder;
import com.oriah.procurement.model.PurchaseOrderLine;
import com.oriah.procurement.model.Supplier;

import io.swagger.v3.oas.annotations.media.Schema;

/** A purchase order as the admin API shows it. */
@Schema(name = "PurchaseOrder")
public record PurchaseOrderResponse(
        UUID id,
        @Schema(description = "The number a person quotes: PO-000123", example = "PO-000123") String reference,
        SupplierSummary supplier,
        LocalDate placedOn,
        @Schema(types = {"string", "null"}) String note,
        @Schema(description = "OPEN, RECEIVED or CANCELLED") String status,
        @Schema(description = "The consignment this order is in, once attached (an attached order is frozen)",
                types = {"string", "null"}) UUID consignmentId,
        @Schema(description = "Everything this order cost, all lines together") BigDecimal totalPaid,
        String currency,
        List<Line> lines,
        @Schema(description = "Counts changes; send it back on a change or cancel") long version,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy) {

    public static final String CURRENCY = "ZMW";

    /** Who the order was placed with, resolved even for a supplier that has since been deleted. */
    @Schema(name = "PurchaseOrderSupplier")
    public record SupplierSummary(UUID id, String name, String country, boolean active, boolean deleted) {

        public static SupplierSummary of(Supplier s) {
            return new SupplierSummary(s.id(), s.name(), s.country(), s.isActive(), s.isDeleted());
        }
    }

    @Schema(name = "PurchaseOrderLine")
    public record Line(
            UUID id,
            int position,
            UUID variantId,
            String sku,
            @Schema(types = {"string", "null"}) String productTitle,
            String variantTitle,
            @Schema(description = "SELLABLE or PACKAGING, as the catalogue said when it was ordered") String kind,
            int quantity,
            @Schema(description = "All-in, as the bank app shows it") BigDecimal totalPaid) {

        static Line of(PurchaseOrderLine l) {
            return new Line(l.id(), l.position(), l.variantId(), l.sku(), l.productTitle(), l.variantTitle(),
                    l.kind(), l.quantity(), l.totalPaid());
        }
    }

    public static PurchaseOrderResponse of(PurchaseOrder po, Supplier supplier) {
        return new PurchaseOrderResponse(po.id(), reference(po.refNo()), SupplierSummary.of(supplier),
                po.placedOn(), po.note(), po.status().name(), po.consignmentId(), po.totalPaid(), CURRENCY,
                po.lines().stream().map(Line::of).toList(),
                po.version() == null ? 0L : po.version(),
                po.createdAt(), po.updatedAt(), po.createdBy(), po.updatedBy());
    }

    /** PO-000123: the database's number, padded to six digits (more digits when it needs them). */
    public static String reference(Long refNo) {
        return refNo == null ? null : String.format("PO-%06d", refNo);
    }
}
