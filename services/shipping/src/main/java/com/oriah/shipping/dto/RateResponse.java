package com.oriah.shipping.dto;

import java.util.List;

import com.oriah.shipping.model.ShippingOption;

/**
 * The options offered for a destination. There are no zones: Oriah delivers
 * within Zambia only, so a zone would be a type with one value, and Phase 7
 * prices delivery per town, not per zone.
 */
public record RateResponse(
        String destinationCountry,
        List<ShippingOption> options
) {}