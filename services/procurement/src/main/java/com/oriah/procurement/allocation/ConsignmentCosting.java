package com.oriah.procurement.allocation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.oriah.procurement.allocation.LandedCostAllocator.CostInput;
import com.oriah.procurement.allocation.LandedCostAllocator.LineInput;
import com.oriah.procurement.allocation.LandedCostAllocator.Result;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostLine;
import com.oriah.procurement.model.CostTreatment;
import com.oriah.procurement.model.CostType;

/**
 * A consignment, costed: its lines and cost lines turned into the allocator's inputs, and the
 * allocator's answer turned back into kwacha a person can read.
 *
 * <p>This is the preview, and the receipt uses the same answer. A consignment that cannot be
 * costed is not an exception: it is a list of PROBLEMS in plain words, each naming the cost
 * line or the line, all together, so they can be fixed in one pass. Nothing is guessed to
 * get past a problem: a missing weight is not treated as zero, a split that no longer covers
 * the lines is not padded.
 *
 * <p>The arithmetic itself, to the ngwee, is {@link LandedCostAllocator}'s and is tested there.
 * Pure: no framework, no I/O.
 */
public final class ConsignmentCosting {

    /** The most a recorded total (NUMERIC(12,2)) can hold: ten whole digits and two decimals. */
    private static final BigDecimal LARGEST_RECORDABLE = new BigDecimal("9999999999.99");

    private ConsignmentCosting() {
    }

    /** {@code quantity} units, each costing {@code unitCost}. */
    public record SliceView(int quantity, BigDecimal unitCost) {
    }

    /** What one line comes to: its goods, the costs put on it, and the unit costs that result. */
    public record LineView(UUID lineId, int position, String sku, int quantity, BigDecimal goodsTotal,
                           BigDecimal sharedCost, BigDecimal landedTotal, List<SliceView> slices) {
    }

    /**
     * One cost as it was shared. {@code perLine} is what it put on each line (by line id, in line
     * order); empty for a PERIOD cost, which is not shared, and while the consignment cannot yet
     * be costed. {@code typed} is true when a person typed the split, and {@code typedBy} and
     * {@code typedAt} say who and when (every override records the actor).
     */
    public record CostView(UUID costLineId, int position, CostType costType, CostBasis basis, CostTreatment treatment,
                           BigDecimal amount, boolean typed, String typedBy, Instant typedAt,
                           Map<UUID, BigDecimal> perLine) {
    }

    /** Exactly what the allocator was given, in whole ngwee: line for line, cost for cost. */
    public record Inputs(List<LineInput> lines, List<CostInput> costs) {
    }

    /**
     * @param ready      whether every problem is cleared and the unit costs below are final
     * @param problems   what stops it, in plain words; empty when ready
     * @param lines      one per consignment line; empty while there are problems
     * @param costs      one per cost line, always (so a screen is never blank)
     * @param inputs     what the allocator was given, so the receipt can record it; null when read
     *                   back from a record, and when there were problems
     */
    public record Outcome(boolean ready, List<String> problems, List<LineView> lines, List<CostView> costs,
                          BigDecimal goodsTotal, BigDecimal capitalizedCostTotal, BigDecimal periodCostTotal,
                          BigDecimal landedTotal, Inputs inputs) {
    }

    public static Outcome of(Consignment consignment) {
        List<ConsignmentLine> lines = consignment.lines();
        List<CostLine> costLines = consignment.costLines();

        List<String> problems = new ArrayList<>();
        if (lines.isEmpty()) {
            problems.add("The consignment has no lines: attach a purchase order, or type the opening stock.");
        }

        List<LineInput> inputs = new ArrayList<>();
        for (ConsignmentLine line : lines) {
            inputs.add(new LineInput(line.quantity(), ngwee(line.goodsTotal()), line.chargeableWeightGrams()));
        }

        List<CostInput> costInputs = new ArrayList<>();
        for (CostLine cost : costLines) {
            costInputs.add(costInput(cost, lines, problems));
        }

        BigDecimal periodTotal = costLines.stream().filter(c -> c.treatment() == CostTreatment.PERIOD)
                .map(CostLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal goodsTotal = lines.stream().map(ConsignmentLine::goodsTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal capitalizedTotal = costLines.stream().filter(c -> c.treatment() == CostTreatment.CAPITALIZE)
                .map(CostLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Each amount fits its column, but their SUM is what is recorded (NUMERIC(12,2)): a total the
        // books cannot hold is said here, in plain words, not as a database error while receiving.
        if (goodsTotal.add(capitalizedTotal).compareTo(LARGEST_RECORDABLE) > 0) {
            problems.add("The consignment is too large to record: its goods and the costs added to them come to K"
                    + goodsTotal.add(capitalizedTotal).toPlainString() + ", and the most one consignment can hold is K"
                    + "9,999,999,999.99. Split it into two consignments.");
        }
        if (periodTotal.compareTo(LARGEST_RECORDABLE) > 0) {
            problems.add("The month's bills on this consignment are too large to record: they come to K"
                    + periodTotal.toPlainString() + ", and the most is K9,999,999,999.99.");
        }

        Result result = null;
        if (problems.isEmpty()) {
            try {
                result = LandedCostAllocator.allocate(inputs, costInputs);
            } catch (IllegalArgumentException refused) {
                // Everything knowable was checked above; this is the allocator's own refusal of what
                // it was handed, and its wording is already plain.
                problems.add(refused.getMessage());
            }
        }

        if (result == null) {
            List<CostView> shown = new ArrayList<>();
            for (CostLine cost : costLines) {
                shown.add(new CostView(cost.id(), cost.position(), cost.costType(), cost.basis(), cost.treatment(),
                        cost.amount(), !cost.shares().isEmpty(), cost.typedBy(), cost.typedAt(), Map.of()));
            }
            return new Outcome(false, List.copyOf(problems), List.of(), shown, goodsTotal, capitalizedTotal,
                    periodTotal, goodsTotal.add(capitalizedTotal), null);
        }

        List<LineView> lineViews = new ArrayList<>();
        BigDecimal landedTotal = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            ConsignmentLine line = lines.get(i);
            LandedCostAllocator.LineResult r = result.lines().get(i);
            List<SliceView> slices = r.slices().stream()
                    .map(s -> new SliceView(s.quantity(), BigDecimal.valueOf(s.unitNgwee(), 2))).toList();
            BigDecimal landed = BigDecimal.valueOf(r.landedNgwee(), 2);
            landedTotal = landedTotal.add(landed);
            lineViews.add(new LineView(line.id(), line.position(), line.sku(), line.quantity(),
                    BigDecimal.valueOf(r.goodsNgwee(), 2), BigDecimal.valueOf(r.sharedCostNgwee(), 2), landed, slices));
        }

        List<CostView> costViews = new ArrayList<>();
        for (int c = 0; c < costLines.size(); c++) {
            CostLine cost = costLines.get(c);
            Map<UUID, BigDecimal> perLine = new LinkedHashMap<>();
            if (cost.treatment() == CostTreatment.CAPITALIZE) {
                long[] shares = result.sharesPerCost().get(c);
                for (int i = 0; i < lines.size(); i++) {
                    perLine.put(lines.get(i).id(), BigDecimal.valueOf(shares[i], 2));
                }
            }
            costViews.add(new CostView(cost.id(), cost.position(), cost.costType(), cost.basis(), cost.treatment(),
                    cost.amount(), !cost.shares().isEmpty(), cost.typedBy(), cost.typedAt(), perLine));
        }
        return new Outcome(true, List.of(), lineViews, costViews, goodsTotal, capitalizedTotal, periodTotal, landedTotal,
                new Inputs(List.copyOf(inputs), List.copyOf(costInputs)));
    }

    /** One cost as the allocator takes it, noting in {@code problems} whatever stops it. */
    private static CostInput costInput(CostLine cost, List<ConsignmentLine> lines, List<String> problems) {
        long amount = ngwee(cost.amount());
        String where = "Cost line " + cost.position() + " (" + cost.costType() + ")";
        if (cost.treatment() == CostTreatment.PERIOD) {
            return new CostInput(amount, cost.basis(), cost.treatment(), null);
        }

        Map<UUID, BigDecimal> typed = cost.shares();
        long[] typedNgwee = null;
        if (!typed.isEmpty()) {
            typedNgwee = new long[lines.size()];
            long sum = 0;
            boolean coversEveryLine = typed.size() == lines.size();
            for (int i = 0; i < lines.size() && coversEveryLine; i++) {
                BigDecimal share = typed.get(lines.get(i).id());
                if (share == null) {
                    coversEveryLine = false;
                } else {
                    typedNgwee[i] = ngwee(share);
                    sum += typedNgwee[i];
                }
            }
            if (!coversEveryLine) {
                problems.add(where + ": the split that was typed no longer covers every line (an order was attached or "
                        + "taken out since). Type it again, naming every line of the consignment.");
                typedNgwee = null;
            } else if (sum != amount) {
                problems.add(where + ": the typed split adds up to K" + BigDecimal.valueOf(sum, 2).toPlainString()
                        + " but the cost is K" + cost.amount().toPlainString() + ".");
            }
            return new CostInput(amount, cost.basis(), cost.treatment(), typedNgwee);
        }

        if (cost.basis() == CostBasis.DIRECT && amount > 0) {
            problems.add(where + ": K" + cost.amount().toPlainString() + " must be typed per line (the basis is DIRECT, "
                    + "so it is never shared out by a rule). Type how much belongs to each line.");
        }
        if (cost.basis() == CostBasis.WEIGHT && amount > 0) {
            List<Integer> missing = new ArrayList<>();
            for (ConsignmentLine line : lines) {
                if (line.chargeableWeightGrams() == null) {
                    missing.add(line.position());
                }
            }
            if (!missing.isEmpty()) {
                problems.add(where + " is shared by weight, but " + (missing.size() == 1 ? "line " : "lines ")
                        + String.join(", ", missing.stream().map(String::valueOf).toList())
                        + (missing.size() == 1 ? " has" : " have") + " no chargeable weight. Key the weight the courier "
                        + "billed on, or share this cost another way.");
            }
        }
        return new CostInput(amount, cost.basis(), cost.treatment(), null);
    }

    private static long ngwee(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }
}
