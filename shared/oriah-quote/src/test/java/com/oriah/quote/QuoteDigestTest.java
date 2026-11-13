package com.oriah.quote;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quote, then commit (MVP_PLAN Phase 0, item 4.4; Q24: "before we commit, we
 * all know the price"). Preview hands the shopper a digest of the whole
 * payable quote; order recomputes it from its own pricing and refuses to save
 * anything when the two differ. So the digest must change whenever what the
 * shopper would pay changes -- and only then.
 *
 * <p>The old {@code PricingVersion} deliberately left out quantity and the
 * delivery amount, so a basket changed in another tab, or a delivery price
 * published between preview and confirm, could be charged unseen. This binds
 * both. Its six properties are carried over below; the rest are new.
 */
class QuoteDigestTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static QuoteLine line(UUID variant, int qty, String price) {
        return new QuoteLine(variant, qty, new BigDecimal(price), ZERO);
    }

    private static Quote selfCollect(QuoteLine... lines) {
        return new Quote(List.of(lines), "ZMW", "SELF_COLLECT", ZERO);
    }

    /** The golden quote: one ring at K100, self-collect, Kwacha. */
    private static final Quote GOLDEN = selfCollect(line(A, 1, "100.00"));

    // ------------------------------------------------------------ pinned form

    @Test
    @DisplayName("the canonical form is pinned, byte for byte (oriah-quote/2: the delivery town and payer after the option, the promotion after the discount)")
    void theCanonicalFormIsPinned() {
        assertThat(QuoteDigest.canonical(GOLDEN)).isEqualTo(
                "oriah-quote/2\u001eZMW\u001eSELF_COLLECT\u001f\u001f\u001f0\u001e100\u001e"
                        + "00000000-0000-0000-0000-00000000000a\u001f1\u001f100\u001f0\u001f\u001e");
    }

    @Test
    @DisplayName("the golden vector is pinned -- computed independently with sha256sum")
    void theGoldenVectorIsPinned() {
        assertThat(QuoteDigest.of(GOLDEN))
                .isEqualTo("aec7f876488fe689849ec2234df2c58030c1ff404eba191a25d1f3d561ee9f4a");
    }

    /** A delivered quote carrying every field the second version added: 2 x K100, K10 off each by a promotion, delivery K125 to a town, paid by Oriah's collecting it. */
    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PROMOTION = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static Quote delivered(UUID town, String payer, UUID promotion) {
        return new Quote(List.of(new QuoteLine(A, 2, HUNDRED, new BigDecimal("10.00"), promotion)), "ZMW", "OWN_DELIVERY", new BigDecimal("125.00"), town, payer);
    }

    @Test
    @DisplayName("a quote that carries a town, a payer and a promotion has its own pinned bytes and digest, also computed independently with sha256sum")
    void theSecondVectorIsPinned() {
        Quote quote = delivered(TOWN, "ORIAH_COLLECTS", PROMOTION);

        assertThat(QuoteDigest.canonical(quote)).isEqualTo(
                "oriah-quote/2\u001eZMW\u001eOWN_DELIVERY\u001f33333333-3333-4333-8333-333333333333\u001fORIAH_COLLECTS\u001f125\u001e305\u001e"
                        + "00000000-0000-0000-0000-00000000000a\u001f2\u001f100\u001f10\u001f44444444-4444-4444-8444-444444444444\u001e");
        assertThat(QuoteDigest.of(quote)).isEqualTo("25873b34c8bfc2245067d652cfcd773db6a98afd2f7854f98b82569670eb5097");
    }

    @Test
    @DisplayName("the delivery town, the payer and the promotion are each bound: changing any one changes the digest")
    void theNewFieldsAreBound() {
        String base = QuoteDigest.of(delivered(TOWN, "ORIAH_COLLECTS", PROMOTION));

        assertThat(QuoteDigest.of(delivered(UUID.fromString("33333333-3333-4333-8333-333333333334"), "ORIAH_COLLECTS", PROMOTION))).as("another town").isNotEqualTo(base);
        assertThat(QuoteDigest.of(delivered(null, "ORIAH_COLLECTS", PROMOTION))).as("no town").isNotEqualTo(base);
        assertThat(QuoteDigest.of(delivered(TOWN, null, PROMOTION))).as("no payer").isNotEqualTo(base);
        assertThat(QuoteDigest.of(delivered(TOWN, "ORIAH_COLLECTS", UUID.fromString("44444444-4444-4444-8444-444444444445")))).as("another promotion").isNotEqualTo(base);
        assertThat(QuoteDigest.of(delivered(TOWN, "ORIAH_COLLECTS", null))).as("the same discount from no promotion").isNotEqualTo(base);
    }

    @Test
    @DisplayName("who pays the courier is bound on its own: two K0 deliveries to one town, differing only in the payer, have two digests")
    void thePayerIsBoundOnItsOwn() {
        Quote oriahCollects = new Quote(List.of(line(A, 1, "100.00")), "ZMW", "YANGO", ZERO, TOWN, Quote.ORIAH_COLLECTS);
        Quote recipientPays = new Quote(List.of(line(A, 1, "100.00")), "ZMW", "YANGO", ZERO, TOWN, Quote.RECIPIENT_PAYS);

        assertThat(QuoteDigest.of(recipientPays)).isNotEqualTo(QuoteDigest.of(oriahCollects));
    }

    @Test
    @DisplayName("a quote built the old way (no town, payer or promotion) is the second version's own form, so a digest made in the first version's form can never be accepted for it")
    void theOldFormCannotBeReplayed() {
        String firstVersionGolden = "532785fb7c7c7c121f8ffd3528d7dffae0db3163403314bc51216c38ca96802c";

        assertThat(QuoteDigest.of(GOLDEN)).isNotEqualTo(firstVersionGolden);
        assertThat(QuoteDigest.canonical(GOLDEN)).startsWith("oriah-quote/2");
    }

    @Test
    @DisplayName("64 lowercase hex characters, the same every time")
    void isStable64LowercaseHex() {
        assertThat(QuoteDigest.of(GOLDEN)).matches(QuoteDigest.PATTERN).isEqualTo(QuoteDigest.of(GOLDEN));
    }

    // ------------------------------------------- carried over from PricingVersion

    @Test
    @DisplayName("the order of the lines does not matter")
    void lineOrderDoesNotMatter() {
        assertThat(QuoteDigest.of(selfCollect(line(A, 1, "100.00"), line(B, 2, "50.00"))))
                .isEqualTo(QuoteDigest.of(selfCollect(line(B, 2, "50.00"), line(A, 1, "100.00"))));
    }

    @Test
    @DisplayName("a price change changes it")
    void aPriceChangeChangesIt() {
        assertThat(QuoteDigest.of(selfCollect(line(A, 1, "100.50"))))
                .isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("trailing zeros do not: 100, 100.0 and 100.00 are one price")
    void trailingZerosDoNotMatter() {
        assertThat(QuoteDigest.of(selfCollect(line(A, 1, "100"))))
                .isEqualTo(QuoteDigest.of(selfCollect(line(A, 1, "100.0"))))
                .isEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("a currency change changes it")
    void aCurrencyChangeChangesIt() {
        assertThat(QuoteDigest.of(new Quote(List.of(line(A, 1, "100.00")), "USD", "SELF_COLLECT", ZERO)))
                .isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("adding or removing a line changes it")
    void addingOrRemovingALineChangesIt() {
        assertThat(QuoteDigest.of(selfCollect(line(A, 1, "100.00"), line(B, 1, "100.00"))))
                .isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("the variant is in it")
    void theVariantIdIsInIt() {
        assertThat(QuoteDigest.of(selfCollect(line(B, 1, "100.00"))))
                .isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    // ----------------------------------------------------- new: what it now binds

    @Test
    @DisplayName("a quantity change changes it -- a basket edited in another tab is not charged unseen")
    void aQuantityChangeChangesIt() {
        assertThat(QuoteDigest.of(selfCollect(line(A, 2, "100.00"))))
                .isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("swapping quantities between two lines changes it even when the total does not")
    void anEqualTotalSwapOfQuantitiesChangesIt() {
        Quote oneAndTwo = selfCollect(line(A, 1, "100.00"), line(B, 2, "100.00"));
        Quote twoAndOne = selfCollect(line(A, 2, "100.00"), line(B, 1, "100.00"));

        assertThat(oneAndTwo.total()).isEqualByComparingTo(twoAndOne.total());
        assertThat(QuoteDigest.of(oneAndTwo)).isNotEqualTo(QuoteDigest.of(twoAndOne));
    }

    @Test
    @DisplayName("the delivery option changes it")
    void theDeliveryOptionChangesIt() {
        assertThat(QuoteDigest.of(new Quote(List.of(line(A, 1, "100.00")), "ZMW", "OWN_DELIVERY", ZERO)))
                .isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("the delivery amount changes it -- a price published between preview and confirm")
    void theDeliveryAmountChangesIt() {
        assertThat(QuoteDigest.of(new Quote(List.of(line(A, 1, "100.00")), "ZMW", "SELF_COLLECT",
                new BigDecimal("125.00")))).isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("a per-piece discount changes it")
    void aPerPieceDiscountChangesIt() {
        assertThat(QuoteDigest.of(selfCollect(new QuoteLine(A, 1, HUNDRED, new BigDecimal("10.00")))))
                .isNotEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("two lines for the same variant still give one stable answer")
    void duplicateLinesSortDeterministically() {
        assertThat(QuoteDigest.of(selfCollect(line(A, 1, "100.00"), line(A, 2, "90.00"))))
                .isEqualTo(QuoteDigest.of(selfCollect(line(A, 2, "90.00"), line(A, 1, "100.00"))));
    }

    @Test
    @DisplayName("the currency is read without regard to case or padding")
    void theCurrencyIsCanonicalised() {
        assertThat(QuoteDigest.of(new Quote(List.of(line(A, 1, "100.00")), " zmw ", "SELF_COLLECT", ZERO)))
                .isEqualTo(QuoteDigest.of(GOLDEN));
    }

    @Test
    @DisplayName("the total is the subtotal, less the discounts, plus delivery: the one formula")
    void theTotalIsSubtotalMinusDiscountPlusDelivery() {
        Quote q = new Quote(List.of(new QuoteLine(A, 2, HUNDRED, new BigDecimal("10.00"))),
                "ZMW", "OWN_DELIVERY", new BigDecimal("125.00"));

        assertThat(q.subtotal()).isEqualByComparingTo("200.00");
        assertThat(q.discount()).isEqualByComparingTo("20.00");
        assertThat(q.total()).isEqualByComparingTo("305.00");
        assertThat(q.total().scale()).isEqualTo(2);
    }

    // ------------------------------------------------------------- refusals

    @Test
    @DisplayName("a quantity below one is refused")
    void aQuantityBelowOneIsRefused() {
        assertThatThrownBy(() -> new QuoteLine(A, 0, HUNDRED, ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a price finer than a ngwee is refused, not rounded into the digest")
    void aPriceFinerThanTheNgweeIsRefused() {
        assertThatThrownBy(() -> new QuoteLine(A, 1, new BigDecimal("100.001"), ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a discount above the price is refused")
    void aDiscountAbovePriceIsRefused() {
        assertThatThrownBy(() -> new QuoteLine(A, 1, HUNDRED, new BigDecimal("100.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a quote with no lines is refused")
    void anEmptyQuoteIsRefused() {
        assertThatThrownBy(() -> new Quote(List.of(), "ZMW", "SELF_COLLECT", ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a delivery option that is not an option code is refused")
    void aMalformedDeliveryOptionIsRefused() {
        assertThatThrownBy(() -> new Quote(List.of(line(A, 1, "100.00")), "ZMW", "self collect", ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative delivery amount is refused")
    void aNegativeDeliveryAmountIsRefused() {
        assertThatThrownBy(() -> new Quote(List.of(line(A, 1, "100.00")), "ZMW", "SELF_COLLECT",
                new BigDecimal("-1.00"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a promotion on a line that takes nothing off is refused: a promotion is only named where it discounts")
    void aPromotionWithNoDiscountIsRefused() {
        assertThatThrownBy(() -> new QuoteLine(A, 1, HUNDRED, ZERO, PROMOTION)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("self-collect has no town and no payer; a payer is one of the two there are; a courier the recipient pays on arrival adds nothing to what is owed now")
    void theDeliveryRules() {
        assertThatThrownBy(() -> new Quote(List.of(line(A, 1, "100.00")), "ZMW", "SELF_COLLECT", ZERO, TOWN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quote(List.of(line(A, 1, "100.00")), "ZMW", "SELF_COLLECT", ZERO, null, "ORIAH_COLLECTS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quote(List.of(line(A, 1, "100.00")), "ZMW", "YANGO", ZERO, TOWN, "NOBODY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quote(List.of(line(A, 1, "100.00")), "ZMW", "YANGO", new BigDecimal("30.00"), TOWN, "RECIPIENT_PAYS"))
                .as("the recipient pays the courier directly: K0 on this order").isInstanceOf(IllegalArgumentException.class);
        assertThat(new Quote(List.of(line(A, 1, "100.00")), "ZMW", "YANGO", ZERO, TOWN, "RECIPIENT_PAYS").total()).isEqualByComparingTo("100.00");
    }
}
