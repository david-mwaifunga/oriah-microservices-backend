package com.oriah.procurement.allocation;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.oriah.procurement.allocation.LandedCostAllocator.CostInput;
import com.oriah.procurement.allocation.LandedCostAllocator.LineInput;
import com.oriah.procurement.allocation.LandedCostAllocator.LineResult;
import com.oriah.procurement.allocation.LandedCostAllocator.Result;
import com.oriah.procurement.allocation.LandedCostAllocator.Slice;
import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostTreatment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each unit of a consignment cost to land, worked in whole ngwee.
 *
 * <p>The figures are MVP_PLAN Part 4, worked by hand: consignment C-001, 7 rings
 * from one supplier for K10,500 all-in and 200 gift boxes from another for
 * K10,000, one courier charge of K6,000 that rides on the goods. Every test here
 * asserts at the UNIT level -- what a unit costs, and that the units add back to
 * exactly what was spent -- because a test that only checks each line's share
 * passes against the broken way of doing it (allocate to the line, divide by
 * quantity, round: K0.86 vanishes and K0.83 of it lands on the box line).
 *
 * <p>All amounts are ngwee, 1/100 of a kwacha: K6,000 is 600,000.
 */
class LandedCostAllocatorTest {

    private static final LineInput RINGS = new LineInput(7, 1_050_000, null);
    private static final LineInput BOXES = new LineInput(200, 1_000_000, null);

    private static CostInput courier(long ngwee, CostBasis basis) {
        return new CostInput(ngwee, basis, CostTreatment.CAPITALIZE, null);
    }

    private static long total(LineResult line) {
        return line.slices().stream().mapToLong(s -> s.quantity() * s.unitNgwee()).sum();
    }

    private static int units(LineResult line) {
        return line.slices().stream().mapToInt(Slice::quantity).sum();
    }

    @Nested
    @DisplayName("MVP_PLAN Part 4, worked by hand")
    class WorkedExamples {

        @Test
        @DisplayName("shared by item (the default): 4 rings at K1,528.99 and 3 at K1,528.98; 110 boxes at K78.99 and 90 at K78.98")
        void sharedByUnits() {
            Result result = LandedCostAllocator.allocate(List.of(RINGS, BOXES),
                    List.of(courier(600_000, CostBasis.UNITS)));

            // The courier's K6,000 split between the two lines by largest remainder:
            // 20,289.86 and 579,710.14 ngwee, so the floors (20,289 + 579,710) leave
            // one ngwee over and it goes to the bigger remainder, the rings.
            assertThat(result.sharesPerCost().get(0)).containsExactly(20_290, 579_710);

            LineResult rings = result.lines().get(0);
            LineResult boxes = result.lines().get(1);
            assertThat(rings.slices()).containsExactly(new Slice(4, 152_899), new Slice(3, 152_898));
            assertThat(boxes.slices()).containsExactly(new Slice(110, 7_899), new Slice(90, 7_898));
            assertThat(rings.landedNgwee()).isEqualTo(1_070_290);
            assertThat(boxes.landedNgwee()).isEqualTo(1_579_710);

            // The invariant, asserted on the units: K20,500 goods + K6,000 courier.
            assertThat(total(rings) + total(boxes)).isEqualTo(2_650_000);
        }

        @Test
        @DisplayName("shared by value: 3 rings at K1,939.03 and 4 at K1,939.02; 83 boxes at K64.64 and 117 at K64.63")
        void sharedByValue() {
            Result result = LandedCostAllocator.allocate(List.of(RINGS, BOXES),
                    List.of(courier(600_000, CostBasis.VALUE)));

            assertThat(result.sharesPerCost().get(0)).containsExactly(307_317, 292_683);
            assertThat(result.lines().get(0).slices())
                    .containsExactly(new Slice(3, 193_903), new Slice(4, 193_902));
            assertThat(result.lines().get(1).slices())
                    .containsExactly(new Slice(83, 6_464), new Slice(117, 6_463));
            assertThat(total(result.lines().get(0)) + total(result.lines().get(1))).isEqualTo(2_650_000);
        }

        @Test
        @DisplayName("the obvious way leaks K0.86; this one does not: units cost exactly what was spent")
        void nothingLeaks() {
            for (CostBasis basis : List.of(CostBasis.UNITS, CostBasis.VALUE)) {
                Result result = LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(courier(600_000, basis)));

                assertThat(result.lines()).extracting(LandedCostAllocatorTest::total)
                        .as("what the units add up to, by %s", basis)
                        .containsExactly(result.lines().get(0).landedNgwee(), result.lines().get(1).landedNgwee());
                assertThat(total(result.lines().get(0)) + total(result.lines().get(1))).isEqualTo(2_650_000);
            }
        }
    }

    @Nested
    @DisplayName("how a line's landed total becomes unit costs")
    class UnitCosts {

        @Test
        @DisplayName("a total that divides evenly is one slice")
        void evenSplitIsOneSlice() {
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(4, 400_000, null)), List.of());

            assertThat(result.lines().get(0).slices()).containsExactly(new Slice(4, 100_000));
        }

        @Test
        @DisplayName("an uneven total is at most two slices, a single ngwee apart, the dearer first")
        void unevenSplitIsTwoSlicesOneNgweeApart() {
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(3, 100_000, null)), List.of());

            // 100,000 / 3 = 33,333.33: one unit carries the odd ngwee.
            assertThat(result.lines().get(0).slices())
                    .containsExactly(new Slice(1, 33_334), new Slice(2, 33_333));
        }

        @Test
        @DisplayName("goods that do not divide by the quantity are shared out too, not rounded away")
        void goodsRemainderIsSharedToo() {
            // K100.00 all-in for 7 pieces is not a whole number of ngwee each.
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(7, 10_000, null)), List.of());

            assertThat(result.lines().get(0).slices())
                    .containsExactly(new Slice(4, 1_429), new Slice(3, 1_428));
            assertThat(total(result.lines().get(0))).isEqualTo(10_000);
        }

        @Test
        @DisplayName("one unit is one slice carrying everything")
        void oneUnit() {
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(1, 12_345, null)), List.of(courier(99, CostBasis.UNITS)));

            assertThat(result.lines().get(0).slices()).containsExactly(new Slice(1, 12_444));
        }
    }

    @Nested
    @DisplayName("ties, zeros and the cost that does not ride on the goods")
    class Edges {

        @Test
        @DisplayName("when two lines tie for the odd ngwee, the earlier line gets it, every time")
        void tiesGoToTheEarlierLine() {
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(1, 0, null), new LineInput(1, 0, null)),
                    List.of(courier(1, CostBasis.UNITS)));

            assertThat(result.sharesPerCost().get(0)).containsExactly(1, 0);
        }

        @Test
        @DisplayName("a PERIOD cost is a bill for the month: it does not touch the unit costs")
        void periodCostsAreLeftOut() {
            Result with = LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    courier(600_000, CostBasis.UNITS),
                    new CostInput(50_000, CostBasis.UNITS, CostTreatment.PERIOD, null)));
            Result without = LandedCostAllocator.allocate(List.of(RINGS, BOXES),
                    List.of(courier(600_000, CostBasis.UNITS)));

            assertThat(with.lines()).isEqualTo(without.lines());
            assertThat(with.sharesPerCost().get(1)).containsExactly(0, 0);
        }

        @Test
        @DisplayName("a cost of zero changes nothing, which is how duty reads today")
        void zeroCostChangesNothing() {
            Result with = LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    courier(600_000, CostBasis.UNITS),
                    new CostInput(0, CostBasis.DIRECT, CostTreatment.CAPITALIZE, new long[] {0, 0})));
            Result without = LandedCostAllocator.allocate(List.of(RINGS, BOXES),
                    List.of(courier(600_000, CostBasis.UNITS)));

            assertThat(with.lines()).isEqualTo(without.lines());
        }

        @Test
        @DisplayName("several costs are each shared by their own basis, and the line shares add up")
        void severalCosts() {
            Result result = LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    courier(600_000, CostBasis.UNITS),
                    courier(100_001, CostBasis.VALUE)));

            long courierShares = result.sharesPerCost().get(0)[0] + result.sharesPerCost().get(0)[1];
            long agentShares = result.sharesPerCost().get(1)[0] + result.sharesPerCost().get(1)[1];
            assertThat(courierShares).isEqualTo(600_000);
            assertThat(agentShares).isEqualTo(100_001);
            assertThat(total(result.lines().get(0)) + total(result.lines().get(1)))
                    .isEqualTo(2_050_000 + 600_000 + 100_001);
        }
    }

    @Nested
    @DisplayName("sharing by weight")
    class ByWeight {

        @Test
        @DisplayName("the line's total chargeable weight, in grams, decides the share (not its quantity)")
        void sharedByWeight() {
            // 10 light pieces weighing 50 g in all, and 2 heavy ones weighing 200 g in all:
            // the shares follow the weights (1 : 4), whatever the quantities are.
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(10, 100_000, 50L), new LineInput(2, 100_000, 200L)),
                    List.of(courier(25_000, CostBasis.WEIGHT)));

            assertThat(result.sharesPerCost().get(0)).containsExactly(5_000, 20_000);
        }

        @Test
        @DisplayName("every line must have a weight, or the cost cannot be shared by weight")
        void aMissingWeightIsRefused() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(
                    List.of(new LineInput(10, 100_000, 5_000L), new LineInput(2, 100_000, null)),
                    List.of(courier(25_000, CostBasis.WEIGHT))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("weight");
        }

        @Test
        @DisplayName("weights too big for a long still share correctly")
        void hugeWeightsDoNotOverflow() {
            long grams = 4_000_000_000_000L; // 4,000,000 tonnes on one line
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(1_000_000, 100_000, grams), new LineInput(1_000_000, 100_000, grams)),
                    List.of(courier(999_999_999_999L, CostBasis.WEIGHT)));

            long[] shares = result.sharesPerCost().get(0);
            assertThat(shares[0] + shares[1]).isEqualTo(999_999_999_999L);
            assertThat(shares[0]).isEqualTo(500_000_000_000L);
        }
    }

    @Nested
    @DisplayName("a split a person typed")
    class TypedShares {

        @Test
        @DisplayName("typed line totals are used instead of the computed ones, and units are still shared by largest remainder")
        void typedSharesReplaceTheComputedOnes() {
            Result result = LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    new CostInput(600_000, CostBasis.UNITS, CostTreatment.CAPITALIZE, new long[] {100_000, 500_000})));

            assertThat(result.sharesPerCost().get(0)).containsExactly(100_000, 500_000);
            // 1,150,000 over 7 rings is 164,285.71: 5 at 164,286 and 2 at 164,285.
            assertThat(result.lines().get(0).slices()).containsExactly(new Slice(5, 164_286), new Slice(2, 164_285));
            assertThat(total(result.lines().get(0)) + total(result.lines().get(1))).isEqualTo(2_650_000);
        }

        @Test
        @DisplayName("a DIRECT cost (duty, surtax, import VAT) must be typed per line")
        void directNeedsTypedShares() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    new CostInput(50_000, CostBasis.DIRECT, CostTreatment.CAPITALIZE, null))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("typed");
        }

        @Test
        @DisplayName("typed shares must add up to the cost exactly, and the refusal says by how much")
        void typedSharesMustAddUp() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    new CostInput(600_000, CostBasis.UNITS, CostTreatment.CAPITALIZE, new long[] {100_000, 499_999}))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("599999")
                    .hasMessageContaining("600000")
                    .hasMessageContaining("1");
        }

        @Test
        @DisplayName("typed shares must name every line, and none may be negative")
        void typedSharesMustCoverEveryLine() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    new CostInput(600_000, CostBasis.UNITS, CostTreatment.CAPITALIZE, new long[] {600_000}))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("every line");
            assertThatThrownBy(() -> LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(
                    new CostInput(600_000, CostBasis.UNITS, CostTreatment.CAPITALIZE, new long[] {700_000, -100_000}))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }

    @Nested
    @DisplayName("what is refused")
    class Refusals {

        @Test
        @DisplayName("a line with no units, or a negative amount, is refused")
        void badLines() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(List.of(new LineInput(0, 100, null)), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LandedCostAllocator.allocate(List.of(new LineInput(1, -1, null)), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LandedCostAllocator.allocate(
                    List.of(RINGS), List.of(new CostInput(-1, CostBasis.UNITS, CostTreatment.CAPITALIZE, null))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("no lines at all is refused")
        void noLines() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a cost shared by weight cannot be shared when every weight is zero, but a cost of zero is simply zero")
        void weightBasisNeedsWeight() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(
                    List.of(new LineInput(1, 100, 0L), new LineInput(1, 100, 0L)),
                    List.of(courier(100, CostBasis.WEIGHT))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("weight");

            Result free = LandedCostAllocator.allocate(
                    List.of(new LineInput(1, 100, 0L), new LineInput(1, 100, 0L)),
                    List.of(courier(0, CostBasis.WEIGHT)));
            assertThat(free.sharesPerCost().get(0)).containsExactly(0, 0);
        }

        @Test
        @DisplayName("the biggest amount a NUMERIC(12,2) column holds, K9,999,999,999.99, is shared without overflow")
        void biggestStorableAmounts() {
            long biggest = 999_999_999_999L;
            Result result = LandedCostAllocator.allocate(
                    List.of(new LineInput(3, biggest, 7L), new LineInput(5, biggest, 11L)),
                    List.of(courier(biggest, CostBasis.VALUE), courier(biggest, CostBasis.WEIGHT), courier(biggest, CostBasis.UNITS)));

            assertThat(total(result.lines().get(0)) + total(result.lines().get(1))).isEqualTo(2 * biggest + 3 * biggest);
        }

        @Test
        @DisplayName("a cost shared by value cannot be shared when no line has any value")
        void valueBasisNeedsValue() {
            assertThatThrownBy(() -> LandedCostAllocator.allocate(
                    List.of(new LineInput(1, 0, null), new LineInput(1, 0, null)),
                    List.of(courier(100, CostBasis.VALUE))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("value");
        }
    }

    @Test
    @DisplayName("a line's units always add up to the units it was given")
    void unitsAddUp() {
        Result result = LandedCostAllocator.allocate(List.of(RINGS, BOXES), List.of(courier(600_000, CostBasis.UNITS)));

        assertThat(units(result.lines().get(0))).isEqualTo(7);
        assertThat(units(result.lines().get(1))).isEqualTo(200);
    }
}
