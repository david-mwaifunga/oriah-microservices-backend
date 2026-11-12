package com.oriah.procurement.allocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.procurement.allocation.LandedCostAllocator.CostInput;
import com.oriah.procurement.allocation.LandedCostAllocator.LineInput;
import com.oriah.procurement.allocation.LandedCostAllocator.LineResult;
import com.oriah.procurement.allocation.LandedCostAllocator.Result;
import com.oriah.procurement.allocation.LandedCostAllocator.Slice;
import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostTreatment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allocator's invariants over thousands of random consignments, not just the
 * two worked by hand.
 *
 * <p>Seeded ({@link Random} with a fixed seed per round), so a failure names the
 * seed and reruns the same consignment. Sizes and amounts stay inside what the
 * database can hold (NUMERIC(12,2): at most 999,999,999,999 ngwee) and include
 * the awkward shapes: one-unit lines, a single line, goods of zero, tiny costs
 * (one ngwee over many lines), and costs far larger than the goods.
 *
 * <p>No property-testing library: it would be a new dependency to pin for what a
 * seeded loop does.
 */
class LandedCostAllocatorPropertyTest {

    private static final int ROUNDS = 4_000;
    private static final long MAX_NGWEE = 999_999_999_999L;

    @Test
    @DisplayName("for any consignment the units cost exactly what was spent, and every shared cost is handed out whole")
    void invariantsHoldForRandomConsignments() {
        for (int seed = 0; seed < ROUNDS; seed++) {
            Random random = new Random(seed);
            List<LineInput> lines = randomLines(random);
            List<CostInput> costs = randomCosts(random, lines);

            Result result = LandedCostAllocator.allocate(lines, costs);

            String why = "seed " + seed;
            long goods = lines.stream().mapToLong(LineInput::goodsNgwee).sum();
            long capitalized = costs.stream()
                    .filter(c -> c.treatment() == CostTreatment.CAPITALIZE).mapToLong(CostInput::amountNgwee).sum();

            // 1. The invariant, on the units.
            long fromUnits = 0;
            for (int i = 0; i < lines.size(); i++) {
                LineResult line = result.lines().get(i);
                int units = 0;
                long money = 0;
                for (Slice slice : line.slices()) {
                    units += slice.quantity();
                    money += slice.quantity() * slice.unitNgwee();
                }
                assertThat(units).as("%s line %d units", why, i).isEqualTo(lines.get(i).quantity());
                assertThat(money).as("%s line %d money", why, i).isEqualTo(line.landedNgwee());
                fromUnits += money;

                // 2. At most two slices, one ngwee apart, the dearer first.
                assertThat(line.slices()).as("%s line %d slices", why, i).hasSizeBetween(1, 2);
                if (line.slices().size() == 2) {
                    assertThat(line.slices().get(0).unitNgwee() - line.slices().get(1).unitNgwee())
                            .as("%s line %d slice gap", why, i).isEqualTo(1);
                    assertThat(line.slices().get(0).quantity()).isPositive();
                    assertThat(line.slices().get(1).quantity()).isPositive();
                }
            }
            assertThat(fromUnits).as("%s: units vs goods + capitalized costs", why).isEqualTo(goods + capitalized);

            // 3. Every shared cost is handed out whole, to non-negative shares.
            for (int c = 0; c < costs.size(); c++) {
                long[] shares = result.sharesPerCost().get(c);
                long sum = 0;
                for (long share : shares) {
                    assertThat(share).as("%s cost %d share", why, c).isGreaterThanOrEqualTo(0);
                    sum += share;
                }
                long expected = costs.get(c).treatment() == CostTreatment.CAPITALIZE ? costs.get(c).amountNgwee() : 0;
                assertThat(sum).as("%s cost %d shares", why, c).isEqualTo(expected);
            }

            // 4. Deterministic: the same input gives the same answer.
            // (A recursive comparison: the result holds long[] arrays, which a record's
            // equals() would compare by reference.)
            assertThat(LandedCostAllocator.allocate(lines, costs)).as(why).usingRecursiveComparison().isEqualTo(result);
        }
    }

    @Test
    @DisplayName("shares of a cost shared by a rule are within one ngwee of the exact proportion")
    void sharesAreFair() {
        for (int seed = 0; seed < ROUNDS; seed++) {
            Random random = new Random(1_000_000L + seed);
            int n = 1 + random.nextInt(6);
            List<LineInput> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                lines.add(new LineInput(1 + random.nextInt(300), random.nextInt(50_000_000), null));
            }
            long amount = random.nextInt(2_000_000);
            Result result = LandedCostAllocator.allocate(lines,
                    List.of(new CostInput(amount, CostBasis.UNITS, CostTreatment.CAPITALIZE, null)));

            long[] shares = result.sharesPerCost().get(0);
            assertLargestRemainder(seed, amount, lines.stream().mapToLong(LineInput::quantity).toArray(), shares);
        }
    }

    /**
     * An oracle that states the PROPERTY instead of repeating the algorithm. For
     * each line the exact share is amount * weight / total weight. A correct
     * largest-remainder answer gives every line either the floor of that or the
     * floor plus one ngwee; a line that got the extra ngwee has a fractional
     * remainder at least as big as every line that did not (strictly bigger than
     * a later line's when they tie, since ties go to the earlier line); and the
     * shares add up to the amount.
     */
    private static void assertLargestRemainder(long seed, long amount, long[] weights, long[] shares) {
        java.math.BigInteger total = java.math.BigInteger.ZERO;
        for (long w : weights) {
            total = total.add(java.math.BigInteger.valueOf(w));
        }
        long sum = 0;
        java.math.BigInteger[] remainder = new java.math.BigInteger[weights.length];
        boolean[] gotExtra = new boolean[weights.length];
        for (int i = 0; i < weights.length; i++) {
            java.math.BigInteger[] qr = java.math.BigInteger.valueOf(amount)
                    .multiply(java.math.BigInteger.valueOf(weights[i])).divideAndRemainder(total);
            long floor = qr[0].longValueExact();
            remainder[i] = qr[1];
            assertThat(shares[i]).as("seed %d line %d is the floor or one more", seed, i)
                    .isBetween(floor, floor + 1);
            gotExtra[i] = shares[i] == floor + 1;
            sum += shares[i];
        }
        assertThat(sum).as("seed %d shares add up", seed).isEqualTo(amount);
        for (int i = 0; i < weights.length; i++) {
            for (int j = 0; j < weights.length; j++) {
                if (gotExtra[i] && !gotExtra[j]) {
                    int order = remainder[i].compareTo(remainder[j]);
                    assertThat(order > 0 || (order == 0 && i < j))
                            .as("seed %d: line %d took the extra ngwee over line %d", seed, i, j).isTrue();
                }
            }
        }
    }

    // ------------------------------------------------------------- generators

    private static List<LineInput> randomLines(Random random) {
        int n = 1 + random.nextInt(8);
        List<LineInput> lines = new ArrayList<>();
        boolean heavy = random.nextInt(10) == 0;
        for (int i = 0; i < n; i++) {
            int quantity = random.nextInt(4) == 0 ? 1 : 1 + random.nextInt(500);
            long goods = switch (random.nextInt(6)) {
                case 0 -> 0;
                case 1 -> random.nextInt(100);
                case 2 -> (long) (random.nextDouble() * MAX_NGWEE / 20);
                default -> random.nextInt(500_000_000);
            };
            Long weight = random.nextInt(8) == 0 && !heavy ? null : 1L + random.nextInt(heavy ? 1_000_000_000 : 5_000_000);
            lines.add(new LineInput(quantity, goods, weight));
        }
        return lines;
    }

    private static List<CostInput> randomCosts(Random random, List<LineInput> lines) {
        boolean allWeighed = lines.stream().allMatch(l -> l.weightGrams() != null);
        boolean anyValue = lines.stream().anyMatch(l -> l.goodsNgwee() > 0);
        int n = random.nextInt(5);
        List<CostInput> costs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long amount = switch (random.nextInt(5)) {
                case 0 -> 0;
                case 1 -> 1 + random.nextInt(3);
                case 2 -> random.nextInt(200);
                case 3 -> (long) (random.nextDouble() * MAX_NGWEE / 20);
                default -> random.nextInt(100_000_000);
            };
            CostTreatment treatment = random.nextInt(5) == 0 ? CostTreatment.PERIOD : CostTreatment.CAPITALIZE;
            CostBasis basis = switch (random.nextInt(4)) {
                case 0 -> CostBasis.UNITS;
                case 1 -> anyValue ? CostBasis.VALUE : CostBasis.UNITS;
                case 2 -> allWeighed ? CostBasis.WEIGHT : CostBasis.UNITS;
                default -> CostBasis.DIRECT;
            };
            long[] typed = null;
            if (basis == CostBasis.DIRECT || random.nextInt(6) == 0) {
                typed = randomPartition(random, amount, lines.size());
            }
            costs.add(new CostInput(amount, basis, treatment, typed));
        }
        return costs;
    }

    /** {@code n} non-negative numbers adding up to {@code total}. */
    private static long[] randomPartition(Random random, long total, int n) {
        long[] parts = new long[n];
        long left = total;
        for (int i = 0; i < n - 1; i++) {
            long part = left == 0 ? 0 : (long) (random.nextDouble() * (left + 1));
            part = Math.min(part, left);
            parts[i] = part;
            left -= part;
        }
        parts[n - 1] = left;
        return parts;
    }
}
