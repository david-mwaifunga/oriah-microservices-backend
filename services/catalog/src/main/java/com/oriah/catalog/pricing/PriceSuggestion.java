package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * A suggested price from a target profit percentage.
 *
 * <p>The client, round 2: the percentage is a profit "of the full landing price", so {@code suggested = landed x (1 + p/100)}. It is
 * rounded UP to the ngwee, so the target is never missed by a rounding. Only ever a suggestion: the typed price stays authoritative.
 */
public final class PriceSuggestion {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(1000);

    private PriceSuggestion() {
    }

    /**
     * @param landedCost          the same dearest-held figure the warning uses; null when no cost is known
     * @param targetProfitPercent 0 to 1000 with at most two decimals; null when none is configured or asked for
     * @return empty when either is missing: nothing is invented
     * @throws IllegalArgumentException a percentage outside 0 to 1000, or with more than two decimals
     */
    public static Optional<BigDecimal> of(BigDecimal landedCost, BigDecimal targetProfitPercent) {
        if (targetProfitPercent != null) {
            if (targetProfitPercent.signum() < 0 || targetProfitPercent.compareTo(MAX_PERCENT) > 0) {
                throw new IllegalArgumentException("targetProfitPercent must be between 0 and 1000, was " + targetProfitPercent.toPlainString());
            }
            if (targetProfitPercent.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("targetProfitPercent may have at most two decimals, was " + targetProfitPercent.toPlainString());
            }
        }
        if (landedCost == null || targetProfitPercent == null) {
            return Optional.empty();
        }
        return Optional.of(landedCost.multiply(HUNDRED.add(targetProfitPercent)).divide(HUNDRED, 2, RoundingMode.CEILING));
    }
}
