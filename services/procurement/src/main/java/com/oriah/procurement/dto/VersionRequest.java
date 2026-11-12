package com.oriah.procurement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A request that carries only the version the person was looking at: cancelling an order,
 * deleting a draft consignment, receiving one. Required, so that "I meant THAT order as it
 * was" is always what is said; a version that has moved on is refused (409).
 */
@Schema(name = "VersionRequest")
public record VersionRequest(

        @NotNull(message = "version is required")
        @PositiveOrZero(message = "version must be 0 or more")
        Long version) {
}
