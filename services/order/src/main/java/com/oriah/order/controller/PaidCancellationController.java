package com.oriah.order.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.CorrectionJob;
import com.oriah.order.service.CorrectionService;
import com.oriah.order.service.Corrections;
import com.oriah.order.service.Corrections.Correction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cancelling a PAID order before it is dispatched, and looking after corrections that are stuck (Phase 12). ORDER_ADMIN only -- held by a manager and
 * the owner, not by a salesperson or a packer. The gateway's ORDER_ADMIN prefix guards these paths; the capability is checked here as well.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Paid cancellations", description = "Cancelling a paid order before it is dispatched, and corrections waiting on the stock system; ORDER_ADMIN")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking ORDER_ADMIN",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('ORDER_ADMIN')")
public class PaidCancellationController {

    /**
     * @param reasonCode why: the customer asked, a piece is no longer available, a mistake of the shop's, or something else
     * @param note       in the person's words, 5 to 200 characters; required when the reason is OTHER. Never sent to the customer or put in an event
     */
    public record CancellationRequest(
            @NotNull(message = "reasonCode is required")
            @Pattern(regexp = "CUSTOMER_ASKED|PIECE_UNAVAILABLE|SHOP_ERROR|OTHER", message = "reasonCode is one of CUSTOMER_ASKED, PIECE_UNAVAILABLE, SHOP_ERROR, OTHER")
            String reasonCode,
            @Size(min = 5, max = 200, message = "the note must be 5 to 200 characters") String note) {
        /** The note is judged as it will be kept, trimmed; a blank one is no note. */
        public CancellationRequest {
            note = note == null || note.isBlank() ? null : note.trim();
        }
    }

    /**
     * A cancellation of a paid order. {@code owedGross = goodsAtList - discounts + deliveryRefunded}: what the customer is owed before any charge for
     * sending it; {@code waiverTakenBack} is never money (a waived delivery was never paid). {@code state}: REQUESTED (the stock system has been asked
     * and has not settled it), DONE, or REFUSED (with {@code refusedCode}: nothing was changed).
     */
    @Schema(name = "PaidCancellation")
    public record CancellationView(UUID correctionId, UUID orderId, String state, String reasonCode, Instant cancelledAt, BigDecimal goodsAtList,
                                   BigDecimal discounts, BigDecimal deliveryRefunded, BigDecimal waiverTakenBack, BigDecimal owedGross, String feeBearer,
                                   String currency, @JsonInclude(JsonInclude.Include.NON_NULL) String refusedCode, boolean replay) {
        static CancellationView of(Correction c, boolean replay) {
            return new CancellationView(c.id(), c.orderId(), c.state(), c.reasonCode(), c.occurredAt(), c.goodsAtList(), c.discounts(), c.deliveryRefunded(),
                    c.waiverTakenBack(), c.owedGross(), c.feeBearer(), c.currency(), c.refusedCode(), replay);
        }
    }

    /** One correction (a paid cancellation or a return) as the list of what is waiting shows it. No note, no actor, no cost. */
    @Schema(name = "CorrectionRow")
    public record CorrectionRow(UUID correctionId, UUID orderId, String kind, String state, String reasonCode, Instant occurredAt, Instant recordedAt,
                                BigDecimal owedGross, String currency, int attempts, @JsonInclude(JsonInclude.Include.NON_NULL) Instant nextAttemptAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String refusedCode, boolean stuck) {
    }

    private final CorrectionService service;
    private final Corrections corrections;
    private final CorrectionJob job;

    public PaidCancellationController(CorrectionService service, Corrections corrections, CorrectionJob job) {
        this.service = service;
        this.corrections = corrections;
        this.job = job;
    }

    @Operation(operationId = "cancelPaidOrder", summary = "Cancel a paid order that has not been dispatched",
            description = "Every piece and its box go back to stock and everything that was paid is owed to the customer, delivery included (a delivery that "
                    + "was waived, or that the recipient pays the courier for, was never paid and is not owed). The order becomes CANCELLED and forgets its "
                    + "recipient and address; the sale itself is never rewritten. Only a PAID or FULFILLED (packed) order: once dispatched or handed over, what "
                    + "comes back is a return. The stock system is asked between two short steps: if its answer settles nothing the reply is 202 with "
                    + "state REQUESTED, nothing else can be done to the order meanwhile, and the same request (or the job, by itself) finishes it. "
                    + "The same reason asked again answers the cancellation that stands (200, replay: true).")
    @ApiResponse(responseCode = "201", description = "Cancelled: the stock is back and the refund is owed")
    @ApiResponse(responseCode = "200", description = "This cancellation was already recorded (replay: true)")
    @ApiResponse(responseCode = "202", description = "Recorded, and the stock system has not settled it yet (state REQUESTED): repeat the request, or wait")
    @ApiResponse(responseCode = "400", description = "Validation failure (the reason is from the list; OTHER needs a note of 5 to 200 characters)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "cancellation-not-allowed (use: a-return | the-cancel-door), already-cancelled, box-swap-in-progress, "
            + "correction-in-progress, payment-being-applied, order-too-old-to-correct, cancellation-refused (the stock system refused and nothing changed)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/cancellation")
    public ResponseEntity<CancellationView> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @Valid @RequestBody CancellationRequest body) {
        Words.requireLength(body.note(), 5, 200, "note");
        if ("OTHER".equals(body.reasonCode()) && body.note() == null) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "note-required", "the reason OTHER needs a note of 5 to 200 characters saying why");
        }
        CorrectionService.Outcome outcome = service.cancel(id, body.reasonCode(), body.note(), Actor.of(jwt));
        Correction correction = outcome.correction();
        HttpStatus status = correction.inFlight() ? HttpStatus.ACCEPTED : outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(CancellationView.of(correction, outcome.replay()));
    }

    @Operation(operationId = "readPaidCancellation", summary = "The latest attempt to cancel this paid order",
            description = "The cancellation that stands, or the last one the stock system refused (state REFUSED, with why).")
    @ApiResponse(responseCode = "200", description = "The cancellation")
    @ApiResponse(responseCode = "404", description = "This order has no paid cancellation",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}/cancellation")
    public CancellationView read(@PathVariable("id") UUID id) {
        return corrections.latestCancellation(id).map(c -> CancellationView.of(c, false))
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation", "orderId", id.toString()));
    }

    @Operation(operationId = "listCorrections", summary = "Paid cancellations and returns, by where they stand with the stock system",
            description = "state is REQUESTED (the default: what is waiting, the longest first; stuck: true once it has waited longer than the threshold), "
                    + "DONE, REFUSED or ALL (newest first). At most 100.")
    @ApiResponse(responseCode = "200", description = "The corrections")
    @ApiResponse(responseCode = "400", description = "invalid-state",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/corrections")
    public List<CorrectionRow> list(@RequestParam(name = "state", defaultValue = "REQUESTED") String state) {
        String wanted = state.strip().toUpperCase(Locale.ROOT);
        if (!List.of(Corrections.REQUESTED, Corrections.DONE, Corrections.REFUSED, "ALL").contains(wanted)) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-state", "state is one of REQUESTED, DONE, REFUSED, ALL");
        }
        return corrections.inState("ALL".equals(wanted) ? null : wanted, 100).stream().map(this::row).toList();
    }

    private CorrectionRow row(Correction c) {
        return new CorrectionRow(c.id(), c.orderId(), c.kind(), c.state(), c.reasonCode(), c.occurredAt(), c.recordedAt(), c.owedGross(), c.currency(),
                c.attempts(), c.nextAttemptAt(), c.refusedCode(), c.inFlight() && job.isStuck(c.recordedAt()));
    }

    @Operation(operationId = "abandonCorrection", summary = "Give up a correction that is stuck with the stock system",
            description = "Allowed only while it is REQUESTED, and only when the stock system itself says it holds no such reversal: then nothing moved, the "
                    + "attempt is REFUSED (abandoned-nothing-moved) and the order is free again. If the stock DID go back the correction is finished instead "
                    + "and the reply is 409 it-did-move. Any other answer from the stock system proves nothing: 503, nothing changed.")
    @ApiResponse(responseCode = "200", description = "Abandoned: nothing had moved")
    @ApiResponse(responseCode = "404", description = "No such correction",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "not-in-flight, or it-did-move (it was finished instead)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "inventory-unavailable: the stock system could not say; nothing was changed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/corrections/{correctionId}/abandon")
    public CorrectionRow abandon(@PathVariable("correctionId") UUID correctionId) {
        return row(service.abandon(correctionId));
    }
}
