package com.oriah.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What admin sends to make a promotion, or to replace one (a promotion is never edited). {@code amount} is a Kwacha amount off each piece
 * (FIXED) or a percentage (PERCENTAGE), in whole ngwee either way; the days are Lusaka calendar days and the last day is included.
 * Whether the scope names its target, whether a percentage is at most 100 and whether the start is in the past are the service's to say.
 */
public record PromotionRequest(
        @NotBlank(message = "name is required") @Size(max = 120, message = "name is at most 120 characters") String name,
        @NotNull(message = "scopeType is required (PRODUCT, CATEGORY or SHOP)") String scopeType,
        /** The product or category; absent for a shop-wide promotion. */
        UUID scopeId,
        @NotNull(message = "discountType is required (FIXED or PERCENTAGE)") String discountType,
        @NotNull(message = "amount is required") @NgweeAmount BigDecimal amount,
        @NotNull(message = "startsOn is required") LocalDate startsOn,
        @NotNull(message = "endsOn is required") LocalDate endsOn) {
}
