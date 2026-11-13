package com.oriah.procurement.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A correction that was recorded: which revision it is, who made it and why, the corrected allocation, and the lines
 * inventory was told about.
 */
@Schema(name = "Amendment")
public record AmendmentResponse(
        @Schema(description = "The new revision's number (2 and up)") int revision,
        String reason,
        @Schema(description = "The Keycloak subject of the person who made it") String amendedBy,
        Instant amendedAt,
        @Schema(description = "Whether the consignment is still an estimate as of this revision") boolean estimated,
        @Schema(description = "The allocation as it now stands: every line's unit costs to the ngwee") AllocationPreviewResponse allocation,
        @Schema(description = "The lines whose cost this changed: each one was announced (consignment.amended) so inventory "
                + "re-lays the new unit costs over the stock. A line not listed is untouched")
        List<UUID> recutLines) {
}
