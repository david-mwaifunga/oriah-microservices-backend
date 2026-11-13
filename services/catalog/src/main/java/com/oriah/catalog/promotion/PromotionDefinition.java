package com.oriah.catalog.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A promotion as the promotion service states it, reduced to what pricing a piece needs. {@code scopeType} is PRODUCT, CATEGORY or SHOP
 * ({@code scopeId} names the product or category, and is null for the whole shop); {@code discountType} is FIXED (a Kwacha amount off each
 * piece) or PERCENTAGE. {@code startsOn} and {@code createdAt} settle a tie between two promotions that discount a piece equally.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromotionDefinition(UUID id, String scopeType, UUID scopeId, String discountType, BigDecimal amount, LocalDate startsOn,
                                  Instant createdAt) {
}
