package com.oriah.procurement.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A stored document's details. The file itself is not here: it is downloaded from
 * {@code /documents/{id}/content}. A document is never changed or deleted; a mistaken one is
 * voided, and says who, when and why.
 */
@Schema(name = "ProcurementDocument")
public record DocumentResponse(
        UUID id,
        @Schema(description = "PURCHASE_ORDER or CONSIGNMENT") String ownerType,
        UUID ownerId,
        @Schema(description = "SUPPLIER_INVOICE, COURIER_INVOICE, ZRA_ASSESSMENT or OTHER") String kind,
        @Schema(description = "As shown to a person: tidied, with an extension that fits the file") String filename,
        @Schema(description = "Read from the file's own first bytes: application/pdf, image/jpeg or image/png",
                example = "application/pdf") String contentType,
        int sizeBytes,
        @Schema(description = "SHA-256 of the file, lower-case hex: the fingerprint to check a copy against") String sha256,
        @Schema(types = {"string", "null"}) String note,
        Instant uploadedAt,
        @Schema(types = {"string", "null"}) String uploadedBy,
        @Schema(description = "Voided documents stay on record and can still be downloaded") boolean voided,
        @Schema(types = {"string", "null"}) Instant voidedAt,
        @Schema(types = {"string", "null"}) String voidedBy,
        @Schema(types = {"string", "null"}) String voidReason) {

    public static final String PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String CONSIGNMENT = "CONSIGNMENT";
}
