package com.oriah.catalog.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which promotion discounts a piece and by how much (round 5: a promotion covers one product, one category with its sub-categories or the
 * whole shop, and is worked out PER PIECE, so no discount is ever split across an order). One promotion applies to a piece, the one that
 * takes the most off; money is whole ngwee, rounded half up, and a piece is never discounted below nothing.
 */
class PromotionEvaluatorTest {

    private static final UUID PRODUCT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_PRODUCT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RINGS = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID JEWELLERY = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID WATCHES = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    private static PromotionDefinition promo(String id, String scope, UUID target, String type, String amount, String starts) {
        return new PromotionDefinition(UUID.fromString(id), scope, target, type, new BigDecimal(amount), LocalDate.parse(starts),
                Instant.parse("2026-11-01T08:00:00Z"));
    }

    private static PromotionDefinition shop(String percent) {
        return promo("00000000-0000-4000-8000-000000000001", "SHOP", null, "PERCENTAGE", percent, "2026-11-20");
    }

    private static Optional<Discount> best(String price, PromotionDefinition... defs) {
        return PromotionEvaluator.best(new BigDecimal(price), PRODUCT, Set.of(RINGS, JEWELLERY), List.of(defs));
    }

    @Test
    @DisplayName("with no promotion in force a piece is not discounted")
    void noPromotion() {
        assertThat(best("4500.00")).isEmpty();
    }

    @Test
    @DisplayName("a shop-wide promotion discounts every piece")
    void shopWide() {
        Discount d = best("4500.00", shop("10.00")).orElseThrow();

        assertThat(d.perPiece()).isEqualByComparingTo("450.00");
        assertThat(d.promotionId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    }

    @Test
    @DisplayName("a product promotion discounts that product and no other")
    void product() {
        var onThis = promo("00000000-0000-4000-8000-000000000002", "PRODUCT", PRODUCT, "FIXED", "100.00", "2026-11-20");
        var onOther = promo("00000000-0000-4000-8000-000000000003", "PRODUCT", OTHER_PRODUCT, "FIXED", "999.00", "2026-11-20");

        Discount d = best("4500.00", onOther, onThis).orElseThrow();

        assertThat(d.promotionId()).isEqualTo(onThis.id());
        assertThat(d.perPiece()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a category promotion discounts a piece in that category or in a category beneath it, and not one elsewhere")
    void category() {
        // The caller hands over the piece's categories WITH their ancestors: this piece is in Rings, which sits under Jewellery.
        var jewellery = promo("00000000-0000-4000-8000-000000000004", "CATEGORY", JEWELLERY, "PERCENTAGE", "5.00", "2026-11-20");
        var watches = promo("00000000-0000-4000-8000-000000000005", "CATEGORY", WATCHES, "PERCENTAGE", "50.00", "2026-11-20");

        Discount d = best("200.00", watches, jewellery).orElseThrow();

        assertThat(d.promotionId()).isEqualTo(jewellery.id());
        assertThat(d.perPiece()).isEqualByComparingTo("10.00");
        assertThat(best("200.00", watches)).as("a watch promotion does not touch a ring").isEmpty();
    }

    @Test
    @DisplayName("a percentage is worked out per piece and rounded half up to the ngwee: 50% of 10.01 is 5.01, not 5.00")
    void percentageRoundsHalfUp() {
        assertThat(best("10.01", shop("50.00")).orElseThrow().perPiece()).isEqualByComparingTo("5.01");
        assertThat(best("99.99", shop("12.50")).orElseThrow().perPiece()).as("12.49875").isEqualByComparingTo("12.50");
        assertThat(best("33.33", shop("15.00")).orElseThrow().perPiece()).as("4.9995").isEqualByComparingTo("5.00");
        assertThat(best("10.01", shop("50.00")).orElseThrow().perPiece().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a Kwacha amount off is never more than the piece costs: the net price is zero at the lowest, never negative")
    void fixedIsCappedAtThePrice() {
        var big = promo("00000000-0000-4000-8000-000000000006", "SHOP", null, "FIXED", "500.00", "2026-11-20");

        assertThat(best("300.00", big).orElseThrow().perPiece()).isEqualByComparingTo("300.00");
        assertThat(best("300.00", promo("00000000-0000-4000-8000-000000000007", "SHOP", null, "PERCENTAGE", "100.00", "2026-11-20")).orElseThrow().perPiece())
                .isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("only one promotion applies to a piece, the one that takes the most off, whatever its scope: they never add up")
    void theLargerDiscountWinsAndTheyDoNotStack() {
        var shopTen = shop("10.00");                                                                                          // 450.00
        var productFixed = promo("00000000-0000-4000-8000-000000000008", "PRODUCT", PRODUCT, "FIXED", "100.00", "2026-11-20");   // 100.00
        var categoryTwenty = promo("00000000-0000-4000-8000-000000000009", "CATEGORY", RINGS, "PERCENTAGE", "20.00", "2026-11-20"); // 900.00

        Discount d = best("4500.00", shopTen, productFixed, categoryTwenty).orElseThrow();

        assertThat(d.promotionId()).isEqualTo(categoryTwenty.id());
        assertThat(d.perPiece()).as("not 450 + 100 + 900").isEqualByComparingTo("900.00");
        assertThat(best("4500.00", shopTen, productFixed).orElseThrow().promotionId()).isEqualTo(shopTen.id());
    }

    @Test
    @DisplayName("the better deal for the customer is judged on THIS piece's price: a Kwacha amount beats a percentage on a cheap piece and loses on a dear one")
    void judgedOnThisPrice() {
        var fixed = promo("00000000-0000-4000-8000-00000000000a", "SHOP", null, "FIXED", "50.00", "2026-11-20");
        var percent = promo("00000000-0000-4000-8000-00000000000b", "SHOP", null, "PERCENTAGE", "10.00", "2026-11-20");

        assertThat(best("200.00", fixed, percent).orElseThrow().promotionId()).as("50.00 against 20.00").isEqualTo(fixed.id());
        assertThat(best("2000.00", fixed, percent).orElseThrow().promotionId()).as("50.00 against 200.00").isEqualTo(percent.id());
    }

    @Test
    @DisplayName("an equal discount is settled by the earlier start, then the lower id, so the answer never depends on the order they arrive in")
    void ties() {
        var early = promo("00000000-0000-4000-8000-0000000000ee", "SHOP", null, "FIXED", "100.00", "2026-11-18");
        var lateLowId = promo("00000000-0000-4000-8000-000000000001", "SHOP", null, "FIXED", "100.00", "2026-11-20");
        var lateHighId = promo("00000000-0000-4000-8000-0000000000ff", "SHOP", null, "FIXED", "100.00", "2026-11-20");

        assertThat(best("4500.00", lateLowId, early, lateHighId).orElseThrow().promotionId()).as("earliest start").isEqualTo(early.id());
        assertThat(best("4500.00", lateHighId, lateLowId).orElseThrow().promotionId()).as("same start: lowest id").isEqualTo(lateLowId.id());
        assertThat(best("4500.00", lateLowId, lateHighId).orElseThrow().promotionId()).isEqualTo(lateLowId.id());
    }

    @Test
    @DisplayName("an id compares as the database orders a uuid (unsigned, as text), not as Java's signed halves: 'ffffffff-...' is the HIGHEST id")
    void idOrderIsTheDatabasesOrder() {
        var high = promo("ffffffff-ffff-4fff-8fff-ffffffffffff", "SHOP", null, "FIXED", "100.00", "2026-11-20");
        var low = promo("00000000-0000-4000-8000-000000000001", "SHOP", null, "FIXED", "100.00", "2026-11-20");

        assertThat(best("4500.00", high, low).orElseThrow().promotionId()).isEqualTo(low.id());
    }

    @Test
    @DisplayName("a promotion that works out to nothing (a tiny percentage of a small price) is not a promotion: no discount and no promotion id")
    void aDiscountOfNothingIsNoDiscount() {
        assertThat(best("0.10", shop("1.00"))).as("1% of 0.10 is 0.001, which rounds to nothing").isEmpty();
    }
}
