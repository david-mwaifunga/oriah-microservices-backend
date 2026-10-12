package com.oriah.catalog.service;

import java.util.List;
import java.util.UUID;

import com.oriah.catalog.dto.VariantPricingResponse;

/** Skeleton only -- behaviour arrives next, driven by VariantPricingServiceTest. */
public interface VariantPricingService {

    VariantPricingResponse priceAll(List<UUID> variantIds);
}
