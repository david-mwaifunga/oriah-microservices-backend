package com.oriah.procurement.allocation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostTreatment;

/**
 * Works out what each unit of a consignment cost to land, in whole ngwee
 * (1/100 of a kwacha; storage is NUMERIC(12,2), which holds every ngwee exactly).
 *
 * <h2>Why this is done on the units and not on the lines</h2>
 *
 * Share a K6,000 courier charge between 7 rings and 200 boxes, divide each
 * line's share by its quantity and round: the line shares add up to K6,000.00
 * exactly, so the obvious test passes, but the unit costs recover only
 * K5,999.14. K0.86 has vanished and 97% of it sits on the box line, because a
 * per-unit rounding error is multiplied by the quantity. The more units a line
 * has, the more it leaks, which is the cheapest, highest-count stock where
 * nobody looks.
 *
 * <p>So there are two passes, each by LARGEST REMAINDER (floor every share, then
 * give one extra ngwee to the largest fractional remainders until the whole is
 * handed out):
 * <ol>
 *   <li>each capitalized cost is shared between the lines by its own basis, or by
 *       the split a person typed;</li>
 *   <li>each line's WHOLE landed total (its all-in goods plus every share) is
 *       shared between its units. That gives at most two unit costs, a single
 *       ngwee apart, so a line comes out as at most two slices.</li>
 * </ol>
 * The goods total goes through the second pass with the shares, so a goods total
 * that does not divide by its quantity is shared out too, not rounded away.
 *
 * <p>The invariant, asserted on the units themselves rather than on the line
 * shares: {@code sum(unit cost x units) == goods + sum of capitalized costs}, to
 * the ngwee.
 *
 * <h2>Rules that make it deterministic</h2>
 * <ul>
 *   <li>Ties for the odd ngwee go to the earlier line (lines are in the order
 *       they were given), and within a line the dearer slice comes first. The
 *       order is the order inventory lays the costs over the units in.</li>
 *   <li>A cost typed by a person must name every line and add up to the cost
 *       EXACTLY; the refusal says by how much it is out. The unit-level pass then
 *       still runs inside each line.</li>
 *   <li>A PERIOD cost is a bill for the month: it is not shared.</li>
 *   <li>Products are formed in {@link BigInteger}: an amount times a weight (or a
 *       goods total) can exceed a long.</li>
 * </ul>
 *
 * <p>Pure: no framework, no clock, no I/O.
 */
public final class LandedCostAllocator {

    private LandedCostAllocator() {
    }

    /**
     * One consignment line: how many, what it cost all-in, and optionally what
     * the line's TOTAL chargeable weight in whole grams (a courier bills the higher
     * of actual and volumetric weight, so this is what was billed on, not a unit's
     * own weight). Needed only for a cost shared by weight.
     */
    public record LineInput(int quantity, long goodsNgwee, Long weightGrams) {
    }

    /**
     * One shared cost. {@code typedSharesNgwee} is null unless a person typed the
     * split (required for {@link CostBasis#DIRECT}, optional override otherwise).
     */
    public record CostInput(long amountNgwee, CostBasis basis, CostTreatment treatment, long[] typedSharesNgwee) {
    }

    /** {@code quantity} units, each costing {@code unitNgwee}. */
    public record Slice(int quantity, long unitNgwee) {
    }

    public record LineResult(long goodsNgwee, long sharedCostNgwee, long landedNgwee, List<Slice> slices) {
    }

    /**
     * @param lines          one per input line, in the same order
     * @param sharesPerCost  one array per input cost, in the same order, each the
     *                       ngwee that cost put on each line (all zero for a
     *                       PERIOD cost)
     */
    public record Result(List<LineResult> lines, List<long[]> sharesPerCost) {
    }

    /**
     * @throws IllegalArgumentException the inputs cannot be allocated (a caller
     *         mistake: no lines, a negative amount, a typed split that does not
     *         add up, a weight missing)
     * @throws AllocationInvariantViolation the arithmetic did not add up (a bug)
     */
    public static Result allocate(List<LineInput> lines, List<CostInput> costs) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("a consignment needs at least one line to allocate");
        }
        for (int i = 0; i < lines.size(); i++) {
            LineInput line = lines.get(i);
            if (line.quantity() < 1) {
                throw new IllegalArgumentException("line " + (i + 1) + " must have at least one unit");
            }
            if (line.goodsNgwee() < 0) {
                throw new IllegalArgumentException("line " + (i + 1) + " has a negative goods total");
            }
            if (line.weightGrams() != null && line.weightGrams() < 0) {
                throw new IllegalArgumentException("line " + (i + 1) + " has a negative weight");
            }
        }

        int n = lines.size();
        long[] shared = new long[n];
        long capitalized = 0;
        List<long[]> sharesPerCost = new ArrayList<>();
        for (CostInput cost : costs) {
            if (cost.amountNgwee() < 0) {
                throw new IllegalArgumentException("a cost cannot be negative");
            }
            if (cost.treatment() == CostTreatment.PERIOD) {
                sharesPerCost.add(new long[n]);
                continue;
            }
            long[] shares = sharesOf(lines, cost);
            sharesPerCost.add(shares);
            capitalized = Math.addExact(capitalized, cost.amountNgwee());
            for (int i = 0; i < n; i++) {
                shared[i] = Math.addExact(shared[i], shares[i]);
            }
        }

        List<LineResult> results = new ArrayList<>(n);
        long goods = 0;
        long landedAll = 0;
        for (int i = 0; i < n; i++) {
            LineInput line = lines.get(i);
            long landed = Math.addExact(line.goodsNgwee(), shared[i]);
            List<Slice> slices = slicesOf(landed, line.quantity());
            results.add(new LineResult(line.goodsNgwee(), shared[i], landed, slices));
            goods = Math.addExact(goods, line.goodsNgwee());
            landedAll = Math.addExact(landedAll, landed);
        }

        assertInvariant(lines, results, Math.addExact(goods, capitalized), landedAll);
        return new Result(List.copyOf(results), List.copyOf(sharesPerCost));
    }

    // ------------------------------------------------------------------ pass 1

    private static long[] sharesOf(List<LineInput> lines, CostInput cost) {
        int n = lines.size();
        long amount = cost.amountNgwee();

        if (cost.typedSharesNgwee() != null) {
            return checkedTypedShares(cost, n);
        }
        if (cost.basis() == CostBasis.DIRECT) {
            if (amount == 0) {
                return new long[n];
            }
            throw new IllegalArgumentException(
                    "a DIRECT cost must have its split typed, line by line: it is never shared out by a rule");
        }
        if (amount == 0) {
            return new long[n];
        }

        BigInteger[] weights = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            LineInput line = lines.get(i);
            weights[i] = switch (cost.basis()) {
                case UNITS -> BigInteger.valueOf(line.quantity());
                case VALUE -> BigInteger.valueOf(line.goodsNgwee());
                case WEIGHT -> {
                    if (line.weightGrams() == null) {
                        throw new IllegalArgumentException(
                                "a cost shared by weight needs a weight on every line; line " + (i + 1) + " has none");
                    }
                    yield BigInteger.valueOf(line.weightGrams());
                }
                case DIRECT -> throw new IllegalStateException("handled above");
            };
        }
        if (Arrays.stream(weights).allMatch(w -> w.signum() == 0)) {
            throw new IllegalArgumentException("a cost cannot be shared by "
                    + (cost.basis() == CostBasis.VALUE ? "value" : "weight")
                    + ": no line has any " + (cost.basis() == CostBasis.VALUE ? "value" : "weight"));
        }
        return largestRemainder(amount, weights);
    }

    private static long[] checkedTypedShares(CostInput cost, int lineCount) {
        long[] typed = cost.typedSharesNgwee();
        if (typed.length != lineCount) {
            throw new IllegalArgumentException("a typed split must name every line: this consignment has "
                    + lineCount + " and " + typed.length + " were given");
        }
        long sum = 0;
        for (long share : typed) {
            if (share < 0) {
                throw new IllegalArgumentException("a typed share cannot be negative");
            }
            sum = Math.addExact(sum, share);
        }
        if (sum != cost.amountNgwee()) {
            long difference = cost.amountNgwee() - sum;
            throw new IllegalArgumentException("the typed shares add up to " + sum + " ngwee but the cost is "
                    + cost.amountNgwee() + " ngwee (" + Math.abs(difference) + " ngwee "
                    + (difference > 0 ? "short" : "over") + ")");
        }
        return typed.clone();
    }

    // ------------------------------------------------------------------ pass 2

    /**
     * A line's whole landed total over its units: at most two costs, base + 1
     * ngwee for the units that take the remainder, then base for the rest.
     */
    private static List<Slice> slicesOf(long landedNgwee, int quantity) {
        long base = landedNgwee / quantity;
        int remainder = (int) (landedNgwee % quantity);
        if (remainder == 0) {
            return List.of(new Slice(quantity, base));
        }
        return List.of(new Slice(remainder, base + 1), new Slice(quantity - remainder, base));
    }

    // ----------------------------------------------------------- the one method

    /**
     * Shares {@code total} between the weights: floor each, then one extra ngwee
     * to the largest fractional remainders, ties to the earlier index.
     */
    static long[] largestRemainder(long total, BigInteger[] weights) {
        BigInteger sum = Arrays.stream(weights).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger whole = BigInteger.valueOf(total);

        int n = weights.length;
        long[] out = new long[n];
        BigInteger[] remainders = new BigInteger[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            BigInteger[] quotientAndRemainder = whole.multiply(weights[i]).divideAndRemainder(sum);
            out[i] = quotientAndRemainder[0].longValueExact();
            remainders[i] = quotientAndRemainder[1];
            assigned += out[i];
        }

        long leftover = total - assigned;
        if (leftover < 0 || leftover >= n) {
            throw new AllocationInvariantViolation("largest remainder left " + leftover + " ngwee over " + n + " lines");
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator
                .comparing((Integer i) -> remainders[i]).reversed()
                .thenComparing(i -> i));
        for (int k = 0; k < leftover; k++) {
            out[order[k]]++;
        }
        return out;
    }

    // --------------------------------------------------------------- the check

    private static void assertInvariant(List<LineInput> lines, List<LineResult> results,
                                        long expectedLandedTotal, long landedFromLines) {
        long fromUnits = 0;
        for (int i = 0; i < results.size(); i++) {
            LineResult result = results.get(i);
            int units = 0;
            long money = 0;
            for (Slice slice : result.slices()) {
                units += slice.quantity();
                money = Math.addExact(money, Math.multiplyExact((long) slice.quantity(), slice.unitNgwee()));
            }
            if (units != lines.get(i).quantity()) {
                throw new AllocationInvariantViolation("line " + (i + 1) + " has " + units + " units in its slices but "
                        + lines.get(i).quantity() + " were received");
            }
            if (money != result.landedNgwee()) {
                throw new AllocationInvariantViolation("line " + (i + 1) + " units add up to " + money
                        + " ngwee but its landed total is " + result.landedNgwee());
            }
            fromUnits = Math.addExact(fromUnits, money);
        }
        if (fromUnits != expectedLandedTotal || landedFromLines != expectedLandedTotal) {
            throw new AllocationInvariantViolation("the units add up to " + fromUnits + " ngwee but goods plus "
                    + "capitalized costs are " + expectedLandedTotal + " ngwee");
        }
    }
}
