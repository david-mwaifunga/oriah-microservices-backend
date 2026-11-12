package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.procurement.allocation.ConsignmentCosting;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the consignment would cost per unit if it were received now.
 *
 * <p>For a DRAFT it is worked out afresh each time. For a RECEIVED consignment it is the allocation that
 * was recorded when the goods landed ({@code revision}), never recomputed.
 *
 * <p>A consignment that cannot be costed yet is not an error: {@code ready} is false, {@code problems}
 * says in plain words what stops it (each naming the cost line or line), and {@code lines} is empty.
 * {@code costs} is always filled so a screen is never blank.
 */
@Schema(name = "AllocationPreview")
public record AllocationPreviewResponse(
        @Schema(description = "Every problem is cleared and the unit costs are final") boolean ready,
        @Schema(description = "What stops it, in plain words; empty when ready") List<String> problems,
        @Schema(description = "One per consignment line; empty while there are problems") List<Line> lines,
        List<Cost> costs,
        BigDecimal goodsTotal,
        BigDecimal capitalizedCostTotal,
        BigDecimal periodCostTotal,
        @Schema(description = "Goods plus capitalized costs: what the units recover, to the ngwee") BigDecimal landedTotal,
        String currency,
        @Schema(description = "Which recorded allocation this is (1 is the receipt itself); null for a draft's preview, "
                + "which is worked out afresh each time", types = {"integer", "null"}) Integer revision,
        @Schema(description = "When the goods landed; null for a draft's preview", types = {"string", "null"})
        Instant receivedAt) {

    /** What one line comes to. {@code slices} are the unit costs: at most two, a ngwee apart, dearer first. */
    @Schema(name = "AllocationLine")
    public record Line(UUID lineId, int position, String sku, int quantity, BigDecimal goodsTotal,
                       @Schema(description = "The costs put on this line") BigDecimal sharedCost,
                       @Schema(description = "Goods plus the costs put on it") BigDecimal landedTotal,
                       List<Slice> slices) {
    }

    /** {@code quantity} units, each costing {@code unitCost}. */
    @Schema(name = "AllocationSlice")
    public record Slice(int quantity, BigDecimal unitCost) {
    }

    /** One cost, and what it put on each line (empty for a PERIOD cost, and while there are problems). */
    @Schema(name = "AllocationCost")
    public record Cost(UUID costLineId, int position, String costType, String basis, String treatment,
                       BigDecimal amount, @Schema(description = "A person typed the split") boolean typed,
                       @Schema(description = "Who typed the split (every override records the actor); null when the "
                               + "allocator shared the cost", types = {"string", "null"}) String typedBy,
                       @Schema(description = "When they typed it", types = {"string", "null"}) Instant typedAt,
                       List<Share> perLine) {
    }

    @Schema(name = "AllocationShare")
    public record Share(UUID lineId, BigDecimal amount) {
    }

    /** A draft's preview, worked out now. */
    public static AllocationPreviewResponse of(ConsignmentCosting.Outcome outcome) {
        return of(outcome, null, null);
    }

    /** A received consignment's recorded allocation, as stored. */
    public static AllocationPreviewResponse of(ConsignmentCosting.Outcome outcome, Integer revision, Instant receivedAt) {
        return new AllocationPreviewResponse(outcome.ready(), outcome.problems(),
                outcome.lines().stream().map(l -> new Line(l.lineId(), l.position(), l.sku(), l.quantity(),
                        l.goodsTotal(), l.sharedCost(), l.landedTotal(),
                        l.slices().stream().map(s -> new Slice(s.quantity(), s.unitCost())).toList())).toList(),
                outcome.costs().stream().map(c -> new Cost(c.costLineId(), c.position(), c.costType().name(),
                        c.basis().name(), c.treatment().name(), c.amount(), c.typed(), c.typedBy(), c.typedAt(),
                        c.perLine().entrySet().stream().map(e -> new Share(e.getKey(), e.getValue())).toList())).toList(),
                outcome.goodsTotal(), outcome.capitalizedCostTotal(), outcome.periodCostTotal(), outcome.landedTotal(),
                ConsignmentResponse.CURRENCY, revision, receivedAt);
    }
}
