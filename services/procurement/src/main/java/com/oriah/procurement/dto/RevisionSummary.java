package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.procurement.service.ConsignmentAllocations.RevisionRow;

import io.swagger.v3.oas.annotations.media.Schema;

/** One revision of a consignment's allocation: the receipt (1) or a correction (2 and up). */
@Schema(name = "AllocationRevision")
public record RevisionSummary(
        int revision,
        @Schema(description = "Why it was corrected; null for the receipt itself", types = {"string", "null"}) String reason,
        @Schema(description = "The Keycloak subject of the person who made it", types = {"string", "null"}) String createdBy,
        Instant createdAt,
        @Schema(description = "Whether the consignment was still an estimate as of this revision") boolean estimated,
        BigDecimal goodsTotal,
        BigDecimal capitalizedCostTotal,
        BigDecimal periodCostTotal,
        @Schema(description = "Goods plus capitalized costs") BigDecimal landedTotal,
        @Schema(description = "The lines whose figures this revision changed from the one before; empty for the receipt")
        List<UUID> changedLines) {

    public static RevisionSummary of(RevisionRow row) {
        return new RevisionSummary(row.revision(), row.reason(), row.createdBy(), row.createdAt(), row.estimated(),
                row.goodsTotal(), row.capitalizedCostTotal(), row.periodCostTotal(), row.landedTotal(), row.changedLines());
    }
}
