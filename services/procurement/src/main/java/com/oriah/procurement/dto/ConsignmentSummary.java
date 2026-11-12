package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentLine;

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
        long version,
        Instant createdAt) {

    public static ConsignmentSummary of(Consignment c) {
        BigDecimal goods = c.lines().stream().map(ConsignmentLine::goodsTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ConsignmentSummary(c.id(), ConsignmentResponse.reference(c.refNo()), c.status().name(), c.isOpening(),
                c.isEstimated(), c.note(), c.orderCount(), c.lines().size(), goods, ConsignmentResponse.CURRENCY,
                c.receivedAt(), c.version() == null ? 0L : c.version(), c.createdAt());
    }
}
