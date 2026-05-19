package com.oriah.shipping.dto;

import java.util.List;

import com.oriah.shipping.model.ShippingOption;
import com.oriah.shipping.model.Zone;

public record RateResponse(
        String destinationCountry,
        Zone zone,
        List<ShippingOption> options
) {}
