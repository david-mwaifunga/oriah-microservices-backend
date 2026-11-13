package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.procurement.allocation.ConsignmentCosting;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.service.ConsignmentAllocations;

import io.swagger.v3.oas.annotations.media.Schema;

/** One row of the consignments list: enough to find the one you want, without every line. */
@Schema(name = "ConsignmentSummary")
public record ConsignmentSummary(
        UUID id,
        @Schema(example = "C-000045") String reference,
        String status,
        boolean opening,
        boolean estimated,
        @Schema(types = {"string", "null"}) String note,
        int orderCount,
        int lineCount,
        @Schema(description = "Every line's all-in goods total") BigDecimal goodsTotal,
        String currency,
        @Schema(types = {"string", "null"}) Instant receivedAt,
        @Schema(description = "Which revision of the allocation stands (1 is the receipt, 2 and up are corrections); null while "
                + "a draft. Above 1, the goods total and the estimate flag are that revision's", types = {"integer", "null"})
        Integer revision,
        long version,
        Instant createdAt) {

    /**
     * @param revision the revision of the recorded allocation that stands, or null for a draft
     * @param state    the recorded inputs of that revision when it is a correction (2 and up), else null: a received
     *                 consignment's own rows are frozen as received, so after a correction the totals come from here
     */
    public static ConsignmentSummary of(Consignment c, Integer revision, ConsignmentAllocations.RevisionState state) {
        BigDecimal goods = state != null
                ? state.lines().stream().map(ConsignmentCosting.LineBasis::goodsTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                : c.lines().stream().map(ConsignmentLine::goodsTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean estimated = state != null ? state.estimated() : c.isEstimated();
        return new ConsignmentSummary(c.id(), ConsignmentResponse.reference(c.refNo()), c.status().name(), c.isOpening(),
                estimated, c.note(), c.orderCount(), c.lines().size(), goods, ConsignmentResponse.CURRENCY,
                c.receivedAt(), revision, c.version() == null ? 0L : c.version(), c.createdAt());
    }
}
