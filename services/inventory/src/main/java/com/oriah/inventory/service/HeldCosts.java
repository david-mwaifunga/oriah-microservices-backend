package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import com.oriah.inventory.service.CostReadService.HeldLotRow;
import com.oriah.inventory.service.CostReadService.HeldVariantCost;

/**
 * The arithmetic of "what did the stock we still hold cost", apart from where the lots come from.
 *
 * <p>The price check compares a price with the DEAREST held lot, not the average: an average can be cleared by a price while
 * the next oldest-first sale still loses money. The average is only shown beside it. Lots are ZMW only (a database CHECK), so
 * there is one currency to name.
 */
final class HeldCosts {

    /** What every lot in the shop is costed in; inventory's own CHECK holds it. */
    static final String CURRENCY = "ZMW";

    private HeldCosts() {
    }

    /**
     * @param onHand the variant's stock count, as the stock row says
     * @param lots   the variant's lots, oldest first; one with nothing left in it is ignored
     */
    static HeldVariantCost summarise(UUID variantId, int onHand, List<HeldLotRow> lots) {
        List<HeldLotRow> held = lots.stream().filter(lot -> lot.qtyRemaining() > 0).toList();
        if (held.isEmpty()) {
            return new HeldVariantCost(variantId, onHand, 0, null, null, null, false, List.of());
        }
        BigDecimal highest = null;
        BigDecimal value = BigDecimal.ZERO;
        int units = 0;
        boolean estimated = false;
        for (HeldLotRow lot : held) {
            if (highest == null || lot.unitCost().compareTo(highest) > 0) {
                highest = lot.unitCost();
            }
            value = value.add(lot.unitCost().multiply(BigDecimal.valueOf(lot.qtyRemaining())));
            units += lot.qtyRemaining();
            estimated |= lot.estimated();
        }
        BigDecimal average = value.divide(BigDecimal.valueOf(units), 2, RoundingMode.HALF_UP);
        return new HeldVariantCost(variantId, onHand, units, highest, average, CURRENCY, estimated, held);
    }
}
