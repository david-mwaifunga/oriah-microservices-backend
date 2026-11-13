package com.oriah.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ask what a set of variants cost AT A PAST INSTANT: a paper sale keyed after the day it was made (the paper tag said one thing and the price
 * has moved since) is priced at its true date, not at today's price.
 *
 * @param at         the moment of the sale; it must have happened (not in the future)
 * @param variantIds capped at 100, like the storefront's read
 */
public record AsOfPricingRequest(
        @NotNull(message = "at is required")
        Instant at,
        @NotEmpty(message = "variantIds must not be empty")
        @Size(max = 100, message = "at most 100 variantIds per request")
        List<UUID> variantIds
) {
}
