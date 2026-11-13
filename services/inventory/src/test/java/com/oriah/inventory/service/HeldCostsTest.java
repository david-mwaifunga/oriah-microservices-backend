package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.inventory.service.CostReadService.HeldLotRow;
import com.oriah.inventory.service.CostReadService.HeldVariantCost;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the pricing screen compares a price with: the dearest unit cost among the lots still holding stock, and the average
 * (shown beside it, never compared with). Pure arithmetic in integer-exact BigDecimal, so it is tested without a database.
 */
class HeldCostsTest {

    private static final UUID VARIANT = UUID.randomUUID();

    private static HeldLotRow lot(String unitCost, int remaining, boolean estimated) {
        return new HeldLotRow(UUID.randomUUID(), new BigDecimal(unitCost), remaining, estimated, 1, Instant.parse("2026-11-09T08:00:00Z"), "C-000045");
    }

    @Test
    @DisplayName("a variant that holds nothing is still answered: no lots, no cost, no currency")
    void nothingHeld() {
        HeldVariantCost cost = HeldCosts.summarise(VARIANT, 0, List.of());

        assertThat(cost.variantId()).isEqualTo(VARIANT);
        assertThat(cost.onHand()).isZero();
        assertThat(cost.heldUnits()).isZero();
        assertThat(cost.lots()).isEmpty();
        assertThat(cost.highestUnitCost()).isNull();
        assertThat(cost.averageUnitCost()).isNull();
        assertThat(cost.currency()).isNull();
        assertThat(cost.estimated()).isFalse();
    }

    @Test
    @DisplayName("the highest is the dearest lot still holding stock, not the oldest and not the average")
    void highestIsTheDearestHeld() {
        HeldVariantCost cost = HeldCosts.summarise(VARIANT, 7, List.of(lot("1528.98", 3, false), lot("1528.99", 4, false)));

        assertThat(cost.highestUnitCost()).isEqualByComparingTo("1528.99");
        assertThat(cost.heldUnits()).isEqualTo(7);
        assertThat(cost.currency()).isEqualTo("ZMW");
    }

    @Test
    @DisplayName("the average is weighted by what is held, rounded half up to the ngwee: 4 x 1528.99 and 3 x 1528.98 average 1528.99")
    void weightedAverage() {
        HeldVariantCost cost = HeldCosts.summarise(VARIANT, 7, List.of(lot("1528.99", 4, false), lot("1528.98", 3, false)));

        // 10702.90 / 7 = 1528.9857...
        assertThat(cost.averageUnitCost()).isEqualByComparingTo("1528.99");
        assertThat(cost.averageUnitCost().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("half an ngwee rounds up, never to even: 0.01 and 0.02 average 0.02")
    void roundsHalfUp() {
        HeldVariantCost cost = HeldCosts.summarise(VARIANT, 2, List.of(lot("0.01", 1, false), lot("0.02", 1, false)));

        assertThat(cost.averageUnitCost()).isEqualByComparingTo("0.02");
    }

    @Test
    @DisplayName("half an ngwee goes up even when the digit before it is even: 0.02 and 0.03 average 0.025, which is 0.03 (half-even would say 0.02)")
    void halfUpNotHalfEven() {
        HeldVariantCost cost = HeldCosts.summarise(VARIANT, 2, List.of(lot("0.02", 1, false), lot("0.03", 1, false)));

        assertThat(cost.averageUnitCost()).isEqualByComparingTo("0.03");
    }

    @Test
    @DisplayName("a lot with nothing left in it is not held, whatever the caller handed over")
    void emptyLotsAreIgnored() {
        HeldVariantCost cost = HeldCosts.summarise(VARIANT, 3, List.of(lot("9000.00", 0, false), lot("100.00", 3, false)));

        assertThat(cost.highestUnitCost()).isEqualByComparingTo("100.00");
        assertThat(cost.lots()).hasSize(1);
        assertThat(cost.heldUnits()).isEqualTo(3);
    }

    @Test
    @DisplayName("estimated is true as soon as any held lot is an estimate, so the screen can say the figure is approximate")
    void estimatedIfAnyLotIs() {
        assertThat(HeldCosts.summarise(VARIANT, 2, List.of(lot("100.00", 1, false), lot("90.00", 1, true))).estimated()).isTrue();
        assertThat(HeldCosts.summarise(VARIANT, 2, List.of(lot("100.00", 1, false), lot("90.00", 1, false))).estimated()).isFalse();
    }

    @Test
    @DisplayName("lots come back in the order they were handed over (oldest first, as the SQL sorts them)")
    void keepsTheOrder() {
        HeldLotRow first = lot("100.00", 1, false);
        HeldLotRow second = lot("200.00", 1, false);

        assertThat(HeldCosts.summarise(VARIANT, 2, List.of(first, second)).lots()).containsExactly(first, second);
    }
}
