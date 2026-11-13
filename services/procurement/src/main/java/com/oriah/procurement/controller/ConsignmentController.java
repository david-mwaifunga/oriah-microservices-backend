package com.oriah.procurement.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.procurement.dto.AllocationPreviewResponse;
import com.oriah.procurement.dto.AmendConsignmentRequest;
import com.oriah.procurement.dto.AmendmentResponse;
import com.oriah.procurement.dto.AttachOrderRequest;
import com.oriah.procurement.dto.ConsignmentResponse;
import com.oriah.procurement.dto.ConsignmentSummary;
import com.oriah.procurement.dto.CostLinesRequest;
import com.oriah.procurement.dto.CreateConsignmentRequest;
import com.oriah.procurement.dto.LineDetailsRequest;
import com.oriah.procurement.dto.OpeningLinesRequest;
import com.oriah.procurement.dto.RevisionSummary;
import com.oriah.procurement.dto.UpdateConsignmentRequest;
import com.oriah.procurement.dto.VersionRequest;
import com.oriah.procurement.model.ConsignmentStatus;
import com.oriah.procurement.service.ConsignmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Building a shipment: whole purchase orders (or the day-one stock typed in) and the costs shared
 * across them. Only the people who hold the procurement capability touch it, costs included:
 * the gateway requires the same capability for this prefix and the service requires it again.
 *
 * <p>The draft half: the preview shows what each unit would cost, and receiving makes it final. After that
 * nothing about the consignment changes; a correction of its cost is a new revision of its allocation
 * ({@code POST .../amendments}, Phase 5), never an edit.
 */
@RestController
@RequestMapping(ConsignmentController.BASE_PATH)
@PreAuthorize("hasRole('PROCUREMENT_ADMIN')")
@Tag(name = "Consignments", description = "One shipment, and the costs shared across it")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the procurement capability",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class ConsignmentController {

    static final String BASE_PATH = "/api/v1/admin/procurement/consignments";

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConsignmentService service;

    public ConsignmentController(ConsignmentService service) {
        this.service = service;
    }

    @Operation(operationId = "createConsignment", summary = "Start a consignment",
            description = "From WHOLE purchase orders (each with the version it was read at; they are frozen from "
                    + "now on), or, with opening = true, as the day-one stock typed in. Exactly one of the two.")
    @ApiResponse(responseCode = "201", description = "The consignment was started; Location points at it")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, or a variant cannot be used; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "A purchase order does not exist",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "An order has changed since that version, is not open, or is already "
            + "in a consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The catalogue could not be asked (opening stock only); nothing was "
            + "saved. Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<ConsignmentResponse> create(@Valid @RequestBody CreateConsignmentRequest request) {
        ConsignmentResponse created = service.create(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
    }

    @Operation(operationId = "listConsignments",
            summary = "The consignments, newest first, optionally only drafts, received ones, or the opening one")
    @ApiResponse(responseCode = "200", description = "A page of consignments")
    @ApiResponse(responseCode = "400",
            description = "size outside 1-100, a negative page or one too far into the list, a status that does not exist, or a sort parameter",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public Page<ConsignmentSummary> list(
            @Parameter(description = "Which page, counting from 0", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "Consignments per page, 1 to 100", schema = @Schema(type = "integer", minimum = "1",
                    maximum = "" + MAX_PAGE_SIZE, defaultValue = "" + DEFAULT_PAGE_SIZE))
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @Parameter(description = "Only consignments in this status") @RequestParam(name = "status", required = false) ConsignmentStatus status,
            @Parameter(description = "true: only the opening consignment; false: all the others") @RequestParam(name = "opening", required = false) Boolean opening,
            @RequestParam(name = "sort", required = false) String sort) {
        // Refused, not clamped or ignored: a quietly changed request hides the caller's mistake.
        if (sort != null) {
            throw new IllegalArgumentException("This endpoint is always ordered newest first and does not accept a "
                    + "'sort' parameter. Remove it.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ", was " + size);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page " + page + " is too far into the list for a size of " + size);
        }
        return service.list(page, size, status, opening);
    }

    @Operation(operationId = "getConsignment", summary = "One consignment, with its orders, lines and costs")
    @ApiResponse(responseCode = "200", description = "The consignment")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public ConsignmentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(operationId = "updateConsignmentHeader", summary = "Replace the header of a draft",
            description = "The note, whether the costs are estimates, and the customs facts (charges basis, declarant, "
                    + "clearance form, ZRA assessment reference, licensed agent) are replaced as a whole: send every "
                    + "field you want kept. Send the version the change was made from.")
    @ApiResponse(responseCode = "200", description = "The consignment as it is now")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, or the charges basis contradicts a duty line; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The consignment has changed since that version, or is not a draft",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/{id}")
    public ConsignmentResponse updateHeader(@PathVariable UUID id, @Valid @RequestBody UpdateConsignmentRequest request) {
        return service.updateHeader(id, request);
    }

    @Operation(operationId = "updateConsignmentLine", summary = "Key what the line records about the goods",
            description = "The chargeable weight (for the whole line, in whole grams), the country the goods came from "
                    + "and the HS code. Replaced as a whole: a field left out is cleared.")
    @ApiResponse(responseCode = "200", description = "The consignment as it is now")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment, or no such line in it",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The consignment has changed since that version, or is not a draft",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/{id}/lines/{lineId}")
    public ConsignmentResponse updateLine(@PathVariable UUID id, @PathVariable UUID lineId,
                                          @Valid @RequestBody LineDetailsRequest request) {
        return service.updateLine(id, lineId, request);
    }

    @Operation(operationId = "replaceOpeningLines", summary = "Replace every line of the opening consignment",
            description = "Only for the opening consignment. Anything typed per line for a cost is cleared. Every "
                    + "variant is checked with the catalogue once.")
    @ApiResponse(responseCode = "200", description = "The consignment as it is now")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, or a variant cannot be used; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The consignment has changed since that version, is not a draft, or "
            + "is not the opening consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The catalogue could not be asked; nothing was changed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/{id}/opening-lines")
    public ConsignmentResponse replaceOpeningLines(@PathVariable UUID id, @Valid @RequestBody OpeningLinesRequest request) {
        return service.replaceOpeningLines(id, request);
    }

    @Operation(operationId = "replaceCostLines", summary = "Replace every cost line of a draft",
            description = "What the courier charged, and any duty, surtax or import VAT (typed, never calculated; zero "
                    + "today). A bank or transfer charge is refused: the supplier price already is the total that left "
                    + "the bank. An empty list clears them.")
    @ApiResponse(responseCode = "200", description = "The consignment as it is now")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, a cost type is not allowed, or a typed split "
            + "does not add up; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The consignment has changed since that version, or is not a draft",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/{id}/cost-lines")
    public ConsignmentResponse replaceCostLines(@PathVariable UUID id, @Valid @RequestBody CostLinesRequest request) {
        return service.replaceCostLines(id, request);
    }

    @Operation(operationId = "attachPurchaseOrder", summary = "Put one more whole purchase order into a draft",
            description = "Its lines are copied onto the consignment and the order is frozen. Send the consignment's "
                    + "version and the order's.")
    @ApiResponse(responseCode = "200", description = "The consignment as it is now")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, or the consignment is full (20 orders, 200 lines)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment or order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A version is stale, the order is not open or is in a consignment "
            + "already, or this is not a draft that takes orders (the opening consignment takes none)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/purchase-orders")
    public ConsignmentResponse attachOrder(@PathVariable UUID id, @Valid @RequestBody AttachOrderRequest request) {
        return service.attachOrder(id, request);
    }

    @Operation(operationId = "detachPurchaseOrder", summary = "Take a purchase order out of a draft",
            description = "Its lines go, the rest are renumbered, anything typed for those lines goes, and the order is "
                    + "free to be changed or cancelled again.")
    @ApiResponse(responseCode = "200", description = "The consignment as it is now")
    @ApiResponse(responseCode = "400", description = "The version is missing",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment or order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The consignment has changed since that version, is not a draft, or "
            + "the order is not in it",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{id}/purchase-orders/{orderId}")
    public ConsignmentResponse detachOrder(@PathVariable UUID id, @PathVariable UUID orderId,
                                           @Parameter(description = "The consignment's version this was decided from", required = true)
                                           @RequestParam(name = "version") long version) {
        return service.detachOrder(id, orderId, version);
    }

    @Operation(operationId = "deleteConsignment", summary = "Delete a draft",
            description = "Its lines and costs go with it and its orders are set free. A received consignment is "
                    + "history and is never deleted.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "The version is missing, or the id is not a UUID",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The consignment has changed since that version, or is already received",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,
                       @Parameter(description = "The consignment's version this was decided from", required = true)
                       @RequestParam(name = "version") long version) {
        service.deleteDraft(id, version);
    }

    @Operation(operationId = "receiveConsignment", summary = "The goods have landed",
            description = "Works out what each unit cost and records it for good (revision 1), makes the consignment "
                    + "and every order in it RECEIVED, and announces each line (consignment.received) for inventory. "
                    + "All or nothing. From now on nothing about it changes. The opening stock must be received before "
                    + "any shipment. Send the version it was decided from.")
    @ApiResponse(responseCode = "200", description = "The consignment, now RECEIVED")
    @ApiResponse(responseCode = "400", description = "The version is missing",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The consignment has changed since that version, is already received, "
            + "cannot be costed yet (the answer says what to fix), or is opening stock and a shipment has already been received",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/receive")
    public ConsignmentResponse receive(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.receive(id, request.version());
    }

    @Operation(operationId = "previewAllocation", summary = "What each unit costs, or would cost if received now",
            description = "The courier's charge and every other cost shared out to the unit, to the ngwee. For a draft it "
                    + "is worked out afresh and changes nothing; when it cannot be costed yet, ready is false and problems "
                    + "says what to fix. For a received consignment it is the allocation recorded when the goods landed "
                    + "(revision, receivedAt), never recomputed; after a correction it is the latest revision unless one is asked for.")
    @ApiResponse(responseCode = "200", description = "The allocation, or the problems that stop it")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID, or the revision is not a number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment, or no such revision of it",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}/allocation")
    public AllocationPreviewResponse preview(@PathVariable UUID id,
                                             @Parameter(description = "A received consignment's earlier revision (1 is the receipt itself); "
                                                     + "left out, the revision that stands")
                                             @RequestParam(name = "revision", required = false) Integer revision) {
        return service.preview(id, revision);
    }

    @Operation(operationId = "amendConsignment", summary = "Correct the cost of a received consignment",
            description = "The courier's bill was keyed wrong, a duty assessment arrived late, a supplier's total was mistyped. "
                    + "Nothing already recorded is edited: this adds a NEW revision of the allocation with the reason and who "
                    + "made it, worked out from the revision before it by the rules a draft is costed by, and announces "
                    + "(consignment.amended) each line whose cost it changed so inventory re-lays the new unit costs over the "
                    + "stock. Send fromRevision, the revision you were looking at, so two people never overwrite each other. "
                    + "costLines, when present, replaces the whole cost list; goodsLines corrects the goods totals it names. "
                    + "All or nothing.")
    @ApiResponse(responseCode = "201", description = "The correction was recorded; Location points at the new revision")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule, a cost is not allowed, a split does not add up, "
            + "or the correction changes nothing; see fieldErrors / detail",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Another correction was made since fromRevision (concurrent-update), or "
            + "the consignment is still a draft",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/amendments")
    public ResponseEntity<AmendmentResponse> amend(@PathVariable UUID id, @Valid @RequestBody AmendConsignmentRequest request) {
        AmendmentResponse amended = service.amend(id, request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + id + "/allocation?revision=" + amended.revision())).body(amended);
    }

    @Operation(operationId = "listConsignmentRevisions", summary = "Every revision of a received consignment's allocation",
            description = "Oldest first: the receipt (1) and each correction with who made it, why, the totals and the lines "
                    + "whose cost it changed. A draft has none.")
    @ApiResponse(responseCode = "200", description = "The revisions")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}/revisions")
    public List<RevisionSummary> revisions(@PathVariable UUID id) {
        return service.revisions(id);
    }
}
