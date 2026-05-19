package com.oriah.promotion.dto;

import java.math.BigDecimal;

/**
 * What checkout receives when it validates+applies a code. Always
 * carries the resulting discount amount; the caller decides whether to
 * commit by calling /redeem afterwards (two-step apply-then-redeem
 * keeps the validate read-only).
 */
public record ApplyResponse(
        String code,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal totalAfterDiscount
) {}
