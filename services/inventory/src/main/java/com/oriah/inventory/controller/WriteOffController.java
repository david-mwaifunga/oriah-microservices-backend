package com.oriah.inventory.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.dto.TakeBackRequest;
import com.oriah.inventory.dto.TakeBackResponse;
import com.oriah.inventory.dto.WriteOffRequest;
import com.oriah.inventory.dto.WriteOffResponse;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.service.StockService;
import com.oriah.observability.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Stock that leaves with no sale (lost, stolen, broken, or given to a model, an influencer, as a gift or a prize), and
 * some of it coming back. Owner only: {@code STOCK_WRITE_OFF}, which a manager does not hold, even though a manager
 * holds INVENTORY_ADMIN. It has its own controller for exactly that reason: the class-level check on the stock
 * controller is INVENTORY_ADMIN.
 *
 * <p>Nothing here shows what the stock cost. The people who may write stock off are not thereby the people who may
 * read cost; that is {@code /api/v1/admin/costs/**}, COST_VIEWER's.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory/write-offs")
@Tag(name = "Write-offs (admin)", description = "Stock leaving with no sale, and coming back; STOCK_WRITE_OFF only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking STOCK_WRITE_OFF",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('STOCK_WRITE_OFF')")
public class WriteOffController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final StockService stock;

    public WriteOffController(StockService stock) {
        this.stock = stock;
    }

    @Operation(operationId = "recordWriteOff",
            summary = "Write stock off, or give it away: the oldest lots, at their cost, released into the month it happened",
            description = "Only stock nobody has reserved can go. The same requestId with the same fields answers again (200) and takes "
                    + "nothing; the same requestId for anything else is a 409.")
    @ApiResponse(responseCode = "201", description = "Recorded; Location is where to read it")
    @ApiResponse(responseCode = "200", description = "A repeat of a request already recorded: the same answer, nothing taken again")
    @ApiResponse(responseCode = "400", description = "A field is missing or wrong (the reason is not on the list; a date in the future or before the stock arrived)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The variant has no stock",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "More than is available, or the requestId was used for something else",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<WriteOffResponse> record(@Valid @RequestBody WriteOffRequest request, @AuthenticationPrincipal Jwt jwt) {
        StockReason reason = StockReason.fromCode(request.reason());
        StockService.WriteOffResult result = stock.writeOff(request.requestId(), request.variantId(), request.qty(), reason,
                        request.occurredAt(), request.note(), actor(jwt))
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "variantId", request.variantId().toString()));
        WriteOffResponse body = WriteOffResponse.from(result);
        if (result.replay()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/admin/inventory/write-offs/" + result.actionId())).body(body);
    }

    @Operation(operationId = "takeBackWriteOff",
            summary = "Some of what was written off or given away comes back: to the lots it left, newest first, at the same cost")
    @ApiResponse(responseCode = "201", description = "Recorded")
    @ApiResponse(responseCode = "200", description = "A repeat of a request already recorded")
    @ApiResponse(responseCode = "400", description = "A field is missing or wrong, or the date is before the write-off or in the future",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such write-off or give-away",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "More than is still out, the action is a sale, or the requestId was used for something else",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{actionId}/take-back")
    public ResponseEntity<TakeBackResponse> takeBack(@PathVariable("actionId") UUID actionId, @Valid @RequestBody TakeBackRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        StockService.TakeBackResult result = stock.takeBack(request.requestId(), actionId, request.qty(), request.occurredAt(), request.note(), actor(jwt))
                .orElseThrow(() -> new ResourceNotFoundException("Write-off", "id", actionId.toString()));
        TakeBackResponse body = TakeBackResponse.from(result);
        return result.replay() ? ResponseEntity.ok(body) : ResponseEntity.status(201).body(body);
    }

    @Operation(operationId = "listWriteOffs",
            summary = "Write-offs and give-aways, newest first, each with what has come back and what is still out")
    @ApiResponse(responseCode = "200", description = "A page of write-offs and give-aways; no cost")
    @ApiResponse(responseCode = "400", description = "Negative page, or size above " + MAX_PAGE_SIZE,
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<com.oriah.inventory.service.StockService.WriteOffRow>> list(
            @RequestParam(name = "variantId", required = false) UUID variantId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        // Rejected rather than clamped, as for every admin list: a caller asking for everything must not get a page and
        // believe it is everything.
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ", was " + size);
        }
        return ResponseEntity.ok(stock.writeOffs(variantId, page, size));
    }

    @Operation(operationId = "getWriteOff", summary = "One write-off or give-away, with what has come back")
    @ApiResponse(responseCode = "200", description = "The write-off")
    @ApiResponse(responseCode = "404", description = "No such write-off or give-away",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{actionId}")
    public ResponseEntity<StockService.WriteOffRow> get(@PathVariable("actionId") UUID actionId) {
        return stock.findWriteOff(actionId).map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Write-off", "id", actionId.toString()));
    }

    private static String actor(Jwt jwt) {
        return jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
    }
}
