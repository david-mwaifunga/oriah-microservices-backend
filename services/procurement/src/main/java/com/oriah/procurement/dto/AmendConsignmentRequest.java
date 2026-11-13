package com.oriah.procurement.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Correcting what a RECEIVED consignment cost. The consignment itself is never edited: this records a new revision of
 * its allocation, with the reason and who made it.
 *
 * <p>Say only what changes. {@code costLines}, when present, REPLACES the cost list as a whole (the same rules as a
 * draft's: send every cost you want kept, the courier's included); left out, the costs stay as the revision before
 * had them. {@code goodsLines} corrects the all-in total of the lines it names; a line it leaves out keeps its total.
 * A correction that changes nothing is refused.
 *
 * @param fromRevision the revision of the allocation the person was looking at; if another correction has been made
 *                     since, this is refused (409) so nobody overwrites what they have not seen
 */
@Schema(name = "AmendConsignmentRequest")
public record AmendConsignmentRequest(

        @NotNull(message = "fromRevision is required")
        @Min(value = 1, message = "fromRevision starts at 1")
        @Schema(description = "The revision of the allocation this correction was made from (the consignment's "
                + "revision field)", example = "1")
        Integer fromRevision,

        @NotBlank(message = "say why it is being corrected")
        @Size(max = 200, message = "reason must be at most 200 characters")
        @Schema(description = "Why, in plain words; kept with the revision", example = "The courier's bill was K600, not K6,000")
        String reason,

        @Schema(description = "Whether the costs are still approximations after this correction. Left out: unchanged. "
                + "The opening stock is an estimate for good and refuses false", types = {"boolean", "null"})
        Boolean estimated,

        @Size(max = 30, message = "a consignment holds at most 30 cost lines")
        @Valid
        @Schema(description = "The whole cost list as it should now be. Left out: the costs are not corrected",
                types = {"array", "null"})
        List<CostLineRequest> costLines,

        @Size(max = 200, message = "a consignment holds at most 200 lines")
        @Valid
        @Schema(description = "Goods totals to correct, by line. The purchase order and its invoice keep what was typed; "
                + "only the cost follows. Left out: no line's goods change", types = {"array", "null"})
        List<GoodsLineRequest> goodsLines) {

    /** One line's corrected all-in goods total. */
    @Schema(name = "AmendGoodsLine")
    public record GoodsLineRequest(

            @NotNull(message = "lineId is required")
            UUID lineId,

            @NotNull(message = "goodsTotal is required")
            @DecimalMin(value = "0.01", message = "goodsTotal must be above zero: goods are never free")
            @NgweeAmount
            @Schema(description = "All-in, as the bank app showed it, for the whole line", example = "10000.00")
            BigDecimal goodsTotal) {
    }
}
