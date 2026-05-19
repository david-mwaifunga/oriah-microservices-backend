package com.oriah.checkout.dto;

import java.math.BigDecimal;
import java.util.List;

public record PreviewResponse(
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal shippingCost,
        BigDecimal total,
        String currency,
        String appliedPromotion,
        List<ShippingOptionDto> shippingOptions,
        String shippingZone
) {
    public record ShippingOptionDto(String carrier, String service, BigDecimal cost,
                                    Integer etaDaysMin, Integer etaDaysMax) {}
}
