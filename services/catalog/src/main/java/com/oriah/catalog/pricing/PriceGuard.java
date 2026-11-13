package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The below-cost rule.
 *
 * <p>A price is compared with the DEAREST unit cost among the lots still holding stock, plus the dearest unit cost among the
 * held lots of the product's default box. Not the average: a price can clear an average while the next oldest-first sale still
 * loses money. Equality is fine. The rule never refuses; it says what it found ({@link PriceAssessment}) and the caller decides
 * what a person must type before saving.
 *
 * <p>The comparison is between two decimals with two places, which is exact: an ngwee is the unit. What is held may change
 * the moment after this reads it (a sale, a receipt, a correction), so the result is "the cost as observed", and anything that
 * needs to know where a price stands now evaluates again.
 */
public final class PriceGuard {

    private PriceGuard() {
    }

    /**
     * The cost alone: what a price would be compared with. Needs no price, so a screen can show it for a variant that has none.
     *
     * @param goods        the variant's held cost, as inventory reports it (an entry with no lots when nothing is held)
     * @param defaultBoxId the product's default box, or null if it has none
     * @param box          that box's held cost; null or with no lots when none is held
     */
    public static CostFigures figures(HeldCost goods, UUID defaultBoxId, HeldCost box) {
        BoxBasis basis;
        BigDecimal boxHighest = null;
        if (defaultBoxId == null) {
            basis = BoxBasis.NONE;
        } else if (box == null || box.highestUnitCost() == null) {
            basis = BoxBasis.NO_HELD_STOCK;
        } else {
            basis = BoxBasis.COUNTED;
            boxHighest = box.highestUnitCost();
        }

        BigDecimal goodsHighest = goods == null ? null : goods.highestUnitCost();
        if (goodsHighest == null) {
            return new CostFigures(null, null, boxHighest, basis, false);
        }
        BigDecimal floor = boxHighest == null ? goodsHighest : goodsHighest.add(boxHighest);
        boolean estimated = setByAnEstimate(goods, goodsHighest) || (basis == BoxBasis.COUNTED && setByAnEstimate(box, boxHighest));
        return new CostFigures(floor, goodsHighest, boxHighest, basis, estimated);
    }

    /**
     * @param price        the price being saved or read
     * @param currency     the price's currency
     * @param goods        the variant's held cost, as inventory reports it (an entry with no lots when nothing is held)
     * @param defaultBoxId the product's default box, or null if it has none
     * @param box          that box's held cost; null or with no lots when none is held
     */
    public static GuardResult evaluate(BigDecimal price, String currency, HeldCost goods, UUID defaultBoxId, HeldCost box) {
        CostFigures figures = figures(goods, defaultBoxId, box);
        if (figures.floor() == null) {
            return new GuardResult(PriceAssessment.NO_COST_KNOWN, price, currency, null, null, null, figures.boxHighest(), figures.boxBasis(), false);
        }
        if (!sameCurrency(currency, goods.currency()) || (figures.boxBasis() == BoxBasis.COUNTED && !sameCurrency(currency, box.currency()))) {
            return new GuardResult(PriceAssessment.CURRENCY_MISMATCH, price, currency, null, null, figures.goodsHighest(), figures.boxHighest(),
                    figures.boxBasis(), false);
        }
        if (price.compareTo(figures.floor()) >= 0) {
            return new GuardResult(PriceAssessment.OK, price, currency, figures.floor(), null, figures.goodsHighest(), figures.boxHighest(),
                    figures.boxBasis(), figures.estimated());
        }
        return new GuardResult(PriceAssessment.BELOW, price, currency, figures.floor(), figures.floor().subtract(price), figures.goodsHighest(),
                figures.boxHighest(), figures.boxBasis(), figures.estimated());
    }

    private static boolean sameCurrency(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /** Whether a lot at the dearest cost, the one that sets the figure, is an estimate. A cheaper estimated lot does not make it a guess. */
    private static boolean setByAnEstimate(HeldCost cost, BigDecimal highest) {
        return cost.lots().stream().anyMatch(lot -> lot.estimated() && lot.unitCost().compareTo(highest) == 0);
    }
}
