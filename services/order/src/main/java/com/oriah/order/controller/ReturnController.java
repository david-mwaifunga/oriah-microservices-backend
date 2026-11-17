package com.oriah.order.controller;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.CorrectionService;
import com.oriah.order.service.Corrections;
import com.oriah.order.service.Corrections.Correction;
import com.oriah.order.service.ReturnViews;
import com.oriah.order.service.ReturnViews.ReturnView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recording and reading returns: pieces that came back after an order was dispatched or handed over (Phase 12). ORDER_ADMIN only -- held by a manager
 * and the owner, not by a salesperson or a packer; a parcel that came back more than 72 hours ago also needs BACKDATE_SALE, read from the token. The
 * gateway's ORDER_ADMIN prefix guards these paths; the capability is checked here as well.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Returns", description = "Pieces that came back after dispatch or hand-over, and what the customer is owed for them; ORDER_ADMIN")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking ORDER_ADMIN (or, for a parcel that came back more than 72 hours ago, BACKDATE_SALE)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('ORDER_ADMIN')")
public class ReturnController {

    /** How many pieces of one line of the order came back. */
    public record ReturnLineRequest(
            @NotNull(message = "lineId is required") UUID lineId,
            @Min(value = 1, message = "quantity must be at least 1") int quantity) {
    }

    /**
     * @param reasonCode       why it came back
     * @param returnedAt       when the parcel came back; now when absent. Never before the order was dispatched (a pickup: handed over), never in the future
     * @param backdateReason   why it is keyed more than 72 hours late (5 to 200 characters); needs BACKDATE_SALE
     * @param note             in the person's words, 5 to 200 characters; required when the reason is OTHER. Never sent to the customer or put in an event
     * @param confirmDuplicate true to record a return of exactly the pieces of one recorded for this order in the last ten minutes
     */
    public record ReturnRequest(
            @NotNull(message = "reasonCode is required")
            @Pattern(regexp = "CHANGED_MIND|WRONG_SIZE|FAULTY|NOT_AS_DESCRIBED|WRONG_ITEM_SENT|DAMAGED_IN_TRANSIT|NOT_DELIVERED|OTHER",
                    message = "reasonCode is one of CHANGED_MIND, WRONG_SIZE, FAULTY, NOT_AS_DESCRIBED, WRONG_ITEM_SENT, DAMAGED_IN_TRANSIT, NOT_DELIVERED, OTHER")
            String reasonCode,
            Instant returnedAt,
            String backdateReason,
            @Size(min = 5, max = 200, message = "the note must be 5 to 200 characters") String note,
            Boolean confirmDuplicate,
            @NotEmpty(message = "at least one line is required") List<@NotNull @Valid ReturnLineRequest> lines) {
        /** The note is judged as it will be kept, trimmed; a blank one is no note. */
        public ReturnRequest {
            note = note == null || note.isBlank() ? null : note.trim();
        }
    }

    private final CorrectionService service;
    private final Corrections corrections;
    private final ReturnViews views;

    public ReturnController(CorrectionService service, Corrections corrections, ReturnViews views) {
        this.service = service;
        this.corrections = corrections;
        this.views = views;
    }

    @Operation(operationId = "recordReturn", summary = "Record pieces that came back after dispatch or hand-over",
            description = "The customer is owed each returned piece's own list price less its own discount: never delivery, never the box. Each piece is put "
                    + "back into the lot it was sold from and held there, not for sale, until it is assessed "
                    + "(POST /api/v1/admin/orders/returns/{returnId}/pieces/{pieceId}/assessment). The order's status does not change and nothing of the sale is "
                    + "rewritten. Only a SHIPPED or DELIVERED order (a paid one that has not gone out is cancelled instead). Dated when the parcel came back. "
                    + "A repeat with the same Idempotency-Key and request answers the same return (200, replay: true). The stock system is asked between two "
                    + "short steps: if its answer settles nothing the reply is 202 with state REQUESTED and the same request, or the job, finishes it.")
    @ApiResponse(responseCode = "201", description = "Recorded: the pieces are back and held for assessment, and the refund is owed")
    @ApiResponse(responseCode = "200", description = "This return was already recorded (replay: true)")
    @ApiResponse(responseCode = "202", description = "Recorded, and the stock system has not settled it yet (state REQUESTED): repeat the request, or wait")
    @ApiResponse(responseCode = "400", description = "Validation failure, a missing or over-long Idempotency-Key, invalid-line, invalid-occurred-at, "
            + "backdate-reason-required, note-required",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "return-not-allowed (use: the-paid-cancellation), more-than-was-sold, similar-return-just-recorded, "
            + "idempotency-key-reused, correction-in-progress, order-too-old-to-correct, return-refused (the stock system refused and nothing changed)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/returns")
    public ResponseEntity<ReturnView> record(@AuthenticationPrincipal Jwt jwt, Authentication authentication, @PathVariable("id") UUID id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @Valid @RequestBody ReturnRequest body) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        if ("OTHER".equals(body.reasonCode()) && body.note() == null) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "note-required", "the reason OTHER needs a note of 5 to 200 characters saying why");
        }
        boolean mayBackdate = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_BACKDATE_SALE".equals(a.getAuthority()));
        Corrections.ReturnAsk ask = new Corrections.ReturnAsk(body.reasonCode(), body.returnedAt(), body.backdateReason(), body.note(),
                Boolean.TRUE.equals(body.confirmDuplicate()),
                body.lines().stream().map(l -> new Corrections.ReturnLine(l.lineId(), l.quantity())).toList());
        CorrectionService.Outcome outcome = service.recordReturn(id, ask, key, Actor.of(jwt), mayBackdate);
        Correction correction = outcome.correction();
        HttpStatus status = correction.inFlight() ? HttpStatus.ACCEPTED : outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(views.of(correction, outcome.replay()));
    }

    @Operation(operationId = "listReturns", summary = "Returns, by where they stand",
            description = "state is AWAITING_ASSESSMENT (the default: received, with a piece nobody has finished looking at, the longest waiting first), "
                    + "IN_FLIGHT (the stock system has not settled them) or ALL (newest first). A page is at most 50. No cost, no note, no actor.")
    @ApiResponse(responseCode = "200", description = "A page of returns")
    @ApiResponse(responseCode = "400", description = "invalid-state",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/returns")
    public ReturnViews.Page list(@RequestParam(name = "state", defaultValue = "AWAITING_ASSESSMENT") String state,
                                 @RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) {
        return views.list(state, page, size);
    }

    @Operation(operationId = "readReturn", summary = "One return: its lines, its pieces and what is owed")
    @ApiResponse(responseCode = "200", description = "The return")
    @ApiResponse(responseCode = "404", description = "No such return",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/returns/{returnId}")
    public ReturnView read(@PathVariable("returnId") UUID returnId) {
        return corrections.find(returnId).filter(c -> Corrections.RETURN.equals(c.kind())).map(c -> views.of(c, false))
                .orElseThrow(() -> new ResourceNotFoundException("Return", "id", returnId.toString()));
    }
}
