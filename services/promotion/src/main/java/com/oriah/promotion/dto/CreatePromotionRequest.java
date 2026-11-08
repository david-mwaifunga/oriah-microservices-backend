package com.oriah.promotion.dto;

import com.oriah.money.NgweeAmount;
import java.math.BigDecimal;
import java.time.Instant;

import com.oriah.promotion.model.PromotionType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePromotionRequest(
        @NotBlank @Size(max = 40) String code,
        @NotNull PromotionType type,
        @NotNull @DecimalMin("0.01") @NgweeAmount BigDecimal amount,
        @Size(max = 255) String description,
        Instant validUntil,
        Integer maxRedemptions
) {}
