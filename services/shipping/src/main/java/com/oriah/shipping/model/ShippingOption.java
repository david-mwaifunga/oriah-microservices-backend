package com.oriah.shipping.model;

import java.math.BigDecimal;

/**
 * Wire shape for a shipping option. Read-only — generated on each
 * /rates call, not persisted.
 */
public record ShippingOption(
        String carrier,
        String service,
        BigDecimal cost,
        String currency,
        int etaDaysMin,
        int etaDaysMax
) {}
