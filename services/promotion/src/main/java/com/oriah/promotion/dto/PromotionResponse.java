package com.oriah.promotion.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.promotion.model.Promotion;
import com.oriah.promotion.model.PromotionType;

public record PromotionResponse(
        UUID id,
        String code,
        PromotionType type,
        BigDecimal amount,
        String description,
        Instant validFrom,
        Instant validUntil,
        Integer maxRedemptions,
        Integer currentRedemptions,
        boolean enabled
) {
    public static PromotionResponse from(Promotion p) {
        return new PromotionResponse(
                p.id(), p.code(), p.type(), p.amount(), p.description(),
                p.validFrom(), p.validUntil(), p.maxRedemptions(),
                p.currentRedemptions(), p.isEnabled()
        );
    }
}
