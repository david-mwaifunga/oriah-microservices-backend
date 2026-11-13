package com.oriah.catalog.promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which promotion discounts a piece, and by how much.
 *
 * <p>A promotion covers one product, one category (with the categories beneath it) or the whole shop, and is worked out PER PIECE: a Kwacha
 * amount off each piece, or a percentage of this piece's price rounded half up to the ngwee. It never takes more than the piece costs.
 * ONE promotion applies to a piece, whichever takes the most off in Kwacha at this price (they never add up); an equal discount is settled by
 * the earlier start and then the lower id, so the answer does not depend on the order the promotions arrive in. A promotion that works out to
 * nothing is no promotion at all (no discount and no promotion id on the line).
 *
 * <p>Pure: the caller hands over what is in force at the instant it cares about and the piece's categories WITH their ancestors, because the
 * category tree is catalog's own and the promotion service does not know it.
 */
public final class PromotionEvaluator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** A promotion's Kwacha off one piece, for the comparison. */
    private record Candidate(PromotionDefinition promotion, BigDecimal perPiece) {
    }

    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparing(Candidate::perPiece).reversed()
            .thenComparing(c -> c.promotion().startsOn())
            // The database orders a uuid as unsigned bytes, which is the order of its text; java.util.UUID compares signed halves.
            .thenComparing(c -> c.promotion().id().toString());

    private PromotionEvaluator() {
    }

    /**
     * @param price       the piece's price (two decimals)
     * @param productId   the product the piece belongs to
     * @param categoryIds every category the product is in, and every category above those
     * @param inForce     the promotions in force at the instant being priced
     */
    public static Optional<Discount> best(BigDecimal price, UUID productId, Set<UUID> categoryIds, List<PromotionDefinition> inForce) {
        return inForce.stream()
                .filter(p -> covers(p, productId, categoryIds))
                .map(p -> new Candidate(p, perPiece(p, price)))
                .filter(c -> c.perPiece().signum() > 0)
                .min(BEST_FIRST)
                .map(c -> new Discount(c.promotion().id(), c.perPiece()));
    }

    private static boolean covers(PromotionDefinition p, UUID productId, Set<UUID> categoryIds) {
        return switch (p.scopeType()) {
            case "SHOP" -> true;
            case "PRODUCT" -> p.scopeId() != null && p.scopeId().equals(productId);
            case "CATEGORY" -> p.scopeId() != null && categoryIds.contains(p.scopeId());
            default -> false;
        };
    }

    private static BigDecimal perPiece(PromotionDefinition p, BigDecimal price) {
        BigDecimal off = switch (p.discountType()) {
            case "FIXED" -> p.amount();
            case "PERCENTAGE" -> price.multiply(p.amount()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            default -> BigDecimal.ZERO;
        };
        return off.min(price).setScale(2, RoundingMode.HALF_UP);
    }
}
