package com.oriah.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.oriah.catalog.pricing.PriceAssessment;

/**
 * The priced pieces that stand at a given assessment, evaluated afresh at every read: a dearer lot received, a cost corrected, a
 * dear lot sold out of, a default box changed all show here the next time anyone looks, with no trigger to forget.
 *
 * @param at     the instant whose promotions were applied: now, or the later moment asked about (to see what a promotion that has not started
 *               yet would do to the prices once it starts)
 * @param counts how many priced variants stand at each assessment, whatever the filter, so a screen can say "3 below cost, 2 with no
 *               cost known" without a second call
 */
public record BelowCostPage(
        Instant at,
        List<BelowCostItem> items,
        Map<PriceAssessment, Long> counts,
        int page,
        int size,
        long totalItems) {
}
