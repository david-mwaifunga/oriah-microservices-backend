package com.oriah.procurement.dto;

import java.time.Instant;
import java.util.UUID;

import com.oriah.procurement.model.Supplier;

import io.swagger.v3.oas.annotations.media.Schema;

/** A supplier as the admin API shows it, with who created and last changed it. */
@Schema(name = "Supplier")
public record SupplierResponse(
        UUID id,
        String name,
        @Schema(description = "ISO 3166-1 alpha-2, upper case", example = "CN") String country,
        @Schema(types = {"string", "null"}) String contactName,
        @Schema(types = {"string", "null"}) String contactDetails,
        @Schema(types = {"string", "null"}) String paymentNotes,
        @Schema(description = "False when Oriah has stopped using this supplier") boolean active,
        @Schema(description = "Counts changes; a lost race between two edits is refused as a 409") long version,
        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "The Keycloak subject of whoever created it, or 'system'") String createdBy,
        String updatedBy) {

    public static SupplierResponse from(Supplier s) {
        return new SupplierResponse(s.id(), s.name(), s.country(), s.contactName(), s.contactDetails(),
                s.paymentNotes(), s.isActive(), s.version() == null ? 0L : s.version(),
                s.createdAt(), s.updatedAt(), s.createdBy(), s.updatedBy());
    }
}
