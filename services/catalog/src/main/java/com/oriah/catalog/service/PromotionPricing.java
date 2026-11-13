package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oriah.catalog.promotion.Discount;
import com.oriah.catalog.promotion.PromotionDefinition;
import com.oriah.catalog.promotion.PromotionEvaluator;
import com.oriah.catalog.repository.CategoryAncestry;

/**
 * Works out the discount each piece gets at an instant.
 *
 * <p>Promotion is asked ONCE for the whole bag (what is in force at the instant, as definitions), and catalog, which owns the category tree and
 * the prices, decides which promotion covers which piece ({@link PromotionEvaluator}). The tree is read only when a category promotion is in
 * force, and once for the distinct products of the bag. A promotion that cannot be asked is a
 * {@link com.oriah.catalog.exception.PromotionsUnavailableException}: nothing is priced, and it is never read as "no promotion".
 */
@Service
public class PromotionPricing {

    /** A piece to be discounted: the variant, the product it belongs to, and its price (the list price at the instant being priced). */
    public record Piece(UUID variantId, UUID productId, BigDecimal price) {
    }

    private final PromotionLookup lookup;
    private final CategoryAncestry ancestry;

    public PromotionPricing(PromotionLookup lookup, CategoryAncestry ancestry) {
        this.lookup = lookup;
        this.ancestry = ancestry;
    }

    /**
     * @param at the instant to price at; null means now
     * @return the discount of each piece that gets one, by variant id; a piece with no discount has no entry
     */
    public Map<UUID, Discount> discountsAt(Collection<Piece> pieces, Instant at) {
        Map<UUID, Discount> discounts = new LinkedHashMap<>();
        if (pieces.isEmpty()) {
            return discounts;
        }
        List<PromotionDefinition> inForce = lookup.inForce(at);
        if (inForce.isEmpty()) {
            return discounts;
        }
        Map<UUID, Set<UUID>> categories = Map.of();
        if (inForce.stream().anyMatch(p -> "CATEGORY".equals(p.scopeType()))) {
            Set<UUID> products = new LinkedHashSet<>();
            pieces.forEach(piece -> products.add(piece.productId()));
            categories = ancestry.categoriesWithAncestors(products);
        }
        for (Piece piece : pieces) {
            Set<UUID> in = categories.getOrDefault(piece.productId(), Set.of());
            PromotionEvaluator.best(piece.price(), piece.productId(), in, inForce)
                    .ifPresent(discount -> discounts.put(piece.variantId(), discount));
        }
        return discounts;
    }
}
