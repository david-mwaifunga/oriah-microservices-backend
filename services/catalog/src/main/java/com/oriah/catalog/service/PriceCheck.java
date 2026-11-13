package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oriah.catalog.dto.CostView;
import com.oriah.catalog.pricing.CostFigures;
import com.oriah.catalog.pricing.GuardResult;
import com.oriah.catalog.pricing.HeldCost;
import com.oriah.catalog.pricing.HeldLot;
import com.oriah.catalog.pricing.PriceAssessment;

/**
 * Reads what the stock a price is compared with cost, and says how that reading is shown and kept.
 *
 * <p>A {@link Reading} is the cost AS OBSERVED at one moment: inventory is asked afresh each time, so what a person is shown, what a
 * save is checked against and what a list reports are never older than the call that made them. What was read is kept with the
 * price it was compared with ({@link #basis}), and a short summary of it goes on the event ({@link #summary}).
 */
@Service
public class PriceCheck {

    /** The goods' and the default box's held cost, read together at {@code evaluatedAt}. {@code box} is null when there is no default box. */
    public record Reading(UUID variantId, UUID boxVariantId, HeldCost goods, HeldCost box, Instant evaluatedAt) {

        public CostFigures figures() {
            return com.oriah.catalog.pricing.PriceGuard.figures(goods, boxVariantId, box);
        }
    }

    private final CostLookup lookup;
    private final Clock clock;

    public PriceCheck(CostLookup lookup, Clock clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    /** Asks inventory, as the caller, about the variant and its default box in ONE call. */
    public Reading read(UUID variantId, UUID boxVariantId, String bearer) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(variantId);
        if (boxVariantId != null) {
            ids.add(boxVariantId);
        }
        Map<UUID, HeldCost> held = lookup.heldCosts(ids, bearer);
        return new Reading(variantId, boxVariantId, held.get(variantId), boxVariantId == null ? null : held.get(boxVariantId), clock.instant());
    }

    /** One call (chunked) for many variants and their boxes: the list that compares every price with the cost. */
    public Map<UUID, HeldCost> readMany(Set<UUID> variantIds, String bearer) {
        return lookup.heldCosts(variantIds, bearer);
    }

    public Instant now() {
        return clock.instant();
    }

    public static CostView costView(Reading reading) {
        CostFigures figures = reading.figures();
        return new CostView(figures.floor(), figures.goodsHighest(), figures.boxHighest(), figures.boxBasis(), figures.estimated(),
                reading.evaluatedAt(), reading.goods(), reading.box());
    }

    /**
     * What the person was shown, kept on the price row: the observed status, the floor and its parts, the lots and their revisions, the
     * default box, the currencies, and when it was read. Schema 1.
     */
    public static Map<String, Object> basis(GuardResult result, Reading reading) {
        Map<String, Object> basis = base(result, reading);
        basis.put("lots", lots(reading.goods()));
        basis.put("boxLots", lots(reading.box()));
        return basis;
    }

    /** The same, shortened for the event: which lots (by id and revision) but not how many units or at what cost each. */
    public static Map<String, Object> summary(GuardResult result, Reading reading) {
        Map<String, Object> summary = base(result, reading);
        summary.put("lots", identities(reading.goods()));
        summary.put("boxLots", identities(reading.box()));
        return summary;
    }

    private static Map<String, Object> base(GuardResult result, Reading reading) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("schema", 1);
        basis.put("status", result.assessment().name());
        basis.put("price", result.price());
        basis.put("currency", result.priceCurrency());
        basis.put("floor", result.floor());
        basis.put("goodsHighest", result.goodsHighest());
        basis.put("boxHighest", result.boxHighest());
        basis.put("boxBasis", result.boxBasis().name());
        basis.put("estimated", result.estimated());
        basis.put("defaultBoxVariantId", reading.boxVariantId());
        basis.put("goodsCurrency", reading.goods() == null ? null : reading.goods().currency());
        basis.put("boxCurrency", reading.box() == null ? null : reading.box().currency());
        basis.put("evaluatedAt", reading.evaluatedAt());
        return basis;
    }

    private static List<Map<String, Object>> lots(HeldCost cost) {
        List<Map<String, Object>> lots = new ArrayList<>();
        if (cost != null) {
            for (HeldLot lot : cost.lots()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("lotId", lot.lotId());
                entry.put("unitCost", lot.unitCost());
                entry.put("qtyRemaining", lot.qtyRemaining());
                entry.put("estimated", lot.estimated());
                entry.put("revision", lot.revision());
                lots.add(entry);
            }
        }
        return lots;
    }

    private static List<Map<String, Object>> identities(HeldCost cost) {
        List<Map<String, Object>> lots = new ArrayList<>();
        if (cost != null) {
            for (HeldLot lot : cost.lots()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("lotId", lot.lotId());
                entry.put("revision", lot.revision());
                lots.add(entry);
            }
        }
        return lots;
    }

    /** Whether the assessment needs a reason before it may be saved: below the cost, or in a currency the lots are not in. */
    public static boolean needsReason(PriceAssessment assessment) {
        return assessment == PriceAssessment.BELOW || assessment == PriceAssessment.CURRENCY_MISMATCH;
    }

    /** The floor a person must have acknowledged, or null when there is none to acknowledge. */
    public static BigDecimal floorOf(GuardResult result) {
        return result.floor();
    }
}
