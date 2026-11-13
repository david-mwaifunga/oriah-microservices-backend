package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The below-cost rule, as pure arithmetic. A price is compared with the DEAREST unit cost among the lots still holding stock,
 * plus the dearest held lot of the product's default box; never the average, which a price can clear while the next
 * oldest-first sale still loses money. Equality is fine. The rule never refuses anything: it only says what it found.
 */
class PriceGuardTest {

    private static final UUID GOODS = UUID.randomUUID();
    private static final UUID BOX = UUID.randomUUID();

    private static HeldLot lot(String unitCost, int remaining, boolean estimated) {
        return new HeldLot(UUID.randomUUID(), new BigDecimal(unitCost), remaining, estimated, 1, Instant.parse("2026-11-09T08:00:00Z"), "C-000045");
    }

    private static HeldCost held(UUID variant, HeldLot... lots) {
        List<HeldLot> held = List.of(lots).stream().filter(l -> l.qtyRemaining() > 0).toList();
        if (held.isEmpty()) {
            return new HeldCost(variant, 0, 0, null, null, null, false, List.of());
        }
        BigDecimal highest = held.stream().map(HeldLot::unitCost).max(BigDecimal::compareTo).orElseThrow();
        int units = held.stream().mapToInt(HeldLot::qtyRemaining).sum();
        return new HeldCost(variant, units, units, highest, highest, "ZMW", held.stream().anyMatch(HeldLot::estimated), held);
    }

    @Test
    @DisplayName("a price at or above the dearest held lot is fine; at it exactly is fine too")
    void atOrAboveIsOk() {
        HeldCost goods = held(GOODS, lot("1528.98", 3, false), lot("1528.99", 4, false));

        assertThat(PriceGuard.evaluate(new BigDecimal("1528.99"), "ZMW", goods, null, null).assessment()).isEqualTo(PriceAssessment.OK);
        GuardResult above = PriceGuard.evaluate(new BigDecimal("2000.00"), "ZMW", goods, null, null);
        assertThat(above.assessment()).isEqualTo(PriceAssessment.OK);
        assertThat(above.floor()).isEqualByComparingTo("1528.99");
        assertThat(above.shortfall()).isNull();
    }

    @Test
    @DisplayName("the cost alone, with no price to compare: the floor and what it is made of, for a variant that is not priced yet")
    void theCostAloneWithoutAPrice() {
        HeldCost goods = held(GOODS, lot("1000.00", 2, true));
        HeldCost box = held(BOX, lot("78.99", 110, false));

        CostFigures figures = PriceGuard.figures(goods, BOX, box);

        assertThat(figures.floor()).isEqualByComparingTo("1078.99");
        assertThat(figures.goodsHighest()).isEqualByComparingTo("1000.00");
        assertThat(figures.boxHighest()).isEqualByComparingTo("78.99");
        assertThat(figures.boxBasis()).isEqualTo(BoxBasis.COUNTED);
        assertThat(figures.estimated()).isTrue();
        assertThat(PriceGuard.figures(held(GOODS), null, null).floor()).as("nothing held, no floor").isNull();
    }

    @Test
    @DisplayName("a price below the dearest held lot is BELOW, and says by how much, even if it clears the cheaper lot and the average")
    void belowTheDearestLot() {
        HeldCost goods = held(GOODS, lot("1528.98", 3, false), lot("1528.99", 4, false));

        GuardResult result = PriceGuard.evaluate(new BigDecimal("1528.98"), "ZMW", goods, null, null);

        assertThat(result.assessment()).isEqualTo(PriceAssessment.BELOW);
        assertThat(result.floor()).isEqualByComparingTo("1528.99");
        assertThat(result.shortfall()).isEqualByComparingTo("0.01");
        assertThat(result.goodsHighest()).isEqualByComparingTo("1528.99");
    }

    @Test
    @DisplayName("the default box's dearest held lot is added: a ring that clears its own cost can still lose money once its box is counted")
    void theBoxIsCounted() {
        HeldCost goods = held(GOODS, lot("1000.00", 2, false));
        HeldCost box = held(BOX, lot("78.98", 90, false), lot("78.99", 110, false));

        GuardResult result = PriceGuard.evaluate(new BigDecimal("1050.00"), "ZMW", goods, BOX, box);

        assertThat(result.assessment()).isEqualTo(PriceAssessment.BELOW);
        assertThat(result.floor()).isEqualByComparingTo("1078.99");
        assertThat(result.boxHighest()).isEqualByComparingTo("78.99");
        assertThat(result.boxBasis()).isEqualTo(BoxBasis.COUNTED);
        assertThat(result.shortfall()).isEqualByComparingTo("28.99");
        assertThat(PriceGuard.evaluate(new BigDecimal("1078.99"), "ZMW", goods, BOX, box).assessment()).isEqualTo(PriceAssessment.OK);
    }

    @Test
    @DisplayName("a piece with no default box is compared on its own cost, and the result says there was no box")
    void noBox() {
        GuardResult result = PriceGuard.evaluate(new BigDecimal("1000.00"), "ZMW", held(GOODS, lot("1000.00", 1, false)), null, null);

        assertThat(result.boxBasis()).isEqualTo(BoxBasis.NONE);
        assertThat(result.boxHighest()).isNull();
        assertThat(result.floor()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a default box with none held adds nothing now, and says so; a dearer box lot later raises the floor without any trigger")
    void aBoxWithNoneHeld() {
        HeldCost goods = held(GOODS, lot("1000.00", 1, false));

        GuardResult result = PriceGuard.evaluate(new BigDecimal("1000.00"), "ZMW", goods, BOX, held(BOX));

        assertThat(result.assessment()).isEqualTo(PriceAssessment.OK);
        assertThat(result.boxBasis()).isEqualTo(BoxBasis.NO_HELD_STOCK);
        assertThat(result.floor()).isEqualByComparingTo("1000.00");
        // the same price, evaluated again once a dear box lot has arrived
        GuardResult later = PriceGuard.evaluate(new BigDecimal("1000.00"), "ZMW", goods, BOX, held(BOX, lot("90.00", 5, false)));
        assertThat(later.assessment()).isEqualTo(PriceAssessment.BELOW);
        assertThat(later.floor()).isEqualByComparingTo("1090.00");
    }

    @Test
    @DisplayName("a variant that holds nothing has no known cost: nothing to compare with, so the answer is NO_COST_KNOWN, never OK")
    void noCostKnown() {
        GuardResult result = PriceGuard.evaluate(new BigDecimal("1.00"), "ZMW", held(GOODS), null, null);

        assertThat(result.assessment()).isEqualTo(PriceAssessment.NO_COST_KNOWN);
        assertThat(result.floor()).isNull();
        assertThat(result.shortfall()).isNull();
    }

    @Test
    @DisplayName("a price in a currency other than the lots' is CURRENCY_MISMATCH: it is never folded into 'no cost known' and never OK")
    void currencyMismatch() {
        GuardResult result = PriceGuard.evaluate(new BigDecimal("0.01"), "USD", held(GOODS, lot("1500.00", 1, false)), null, null);

        assertThat(result.assessment()).isEqualTo(PriceAssessment.CURRENCY_MISMATCH);
        assertThat(result.priceCurrency()).isEqualTo("USD");
        assertThat(result.floor()).isNull();
    }

    @Test
    @DisplayName("the figure is approximate only if a lot that SETS it is an estimate: an estimated cheaper lot does not make the dearest one a guess")
    void estimatedIsAboutTheLotsThatSetTheFloor() {
        HeldCost estimatedDearest = held(GOODS, lot("100.00", 1, false), lot("120.00", 1, true));
        HeldCost estimatedCheaper = held(GOODS, lot("100.00", 1, true), lot("120.00", 1, false));

        assertThat(PriceGuard.evaluate(new BigDecimal("150.00"), "ZMW", estimatedDearest, null, null).estimated()).isTrue();
        assertThat(PriceGuard.evaluate(new BigDecimal("150.00"), "ZMW", estimatedCheaper, null, null).estimated()).isFalse();
        // an estimated box lot at the box's dearest cost counts too
        assertThat(PriceGuard.evaluate(new BigDecimal("150.00"), "ZMW", estimatedCheaper, BOX, held(BOX, lot("5.00", 1, true))).estimated()).isTrue();
    }

    @Test
    @DisplayName("against a brute-force reference over thousands of random shops: same assessment, same floor, same shortfall")
    void againstABruteForceReference() {
        Random random = new Random(20261113L);
        for (int i = 0; i < 3000; i++) {
            List<HeldLot> goodsLots = randomLots(random);
            List<HeldLot> boxLots = randomLots(random);
            boolean hasBox = random.nextInt(3) != 0;
            long price = random.nextInt(400_000);   // ngwee, K0.00 to K3,999.99

            HeldCost goods = held(GOODS, goodsLots.toArray(HeldLot[]::new));
            HeldCost box = hasBox ? held(BOX, boxLots.toArray(HeldLot[]::new)) : null;
            GuardResult actual = PriceGuard.evaluate(ngwee(price), "ZMW", goods, hasBox ? BOX : null, box);

            long goodsHighest = goodsLots.stream().filter(l -> l.qtyRemaining() > 0).mapToLong(l -> l.unitCost().movePointRight(2).longValueExact()).max().orElse(-1);
            long boxHighest = hasBox ? boxLots.stream().filter(l -> l.qtyRemaining() > 0).mapToLong(l -> l.unitCost().movePointRight(2).longValueExact()).max().orElse(0) : 0;
            if (goodsHighest < 0) {
                assertThat(actual.assessment()).as("case %d", i).isEqualTo(PriceAssessment.NO_COST_KNOWN);
                continue;
            }
            long floor = goodsHighest + boxHighest;
            assertThat(actual.floor()).as("case %d floor", i).isEqualByComparingTo(ngwee(floor));
            if (price >= floor) {
                assertThat(actual.assessment()).as("case %d", i).isEqualTo(PriceAssessment.OK);
                assertThat(actual.shortfall()).isNull();
            } else {
                assertThat(actual.assessment()).as("case %d", i).isEqualTo(PriceAssessment.BELOW);
                assertThat(actual.shortfall()).as("case %d shortfall", i).isEqualByComparingTo(ngwee(floor - price));
            }
        }
    }

    private static List<HeldLot> randomLots(Random random) {
        List<HeldLot> lots = new ArrayList<>();
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            lots.add(lot(ngwee(1 + random.nextInt(300_000)).toPlainString(), random.nextInt(4), random.nextBoolean()));
        }
        return lots;
    }

    private static BigDecimal ngwee(long amount) {
        return BigDecimal.valueOf(amount, 2);
    }
}
