package com.oriah.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.PreviewOrderRequest;
import com.oriah.order.dto.RecordOrderRequest;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.dto.RecordedOrderView;
import com.oriah.order.dto.RecordedViews;
import com.oriah.order.service.RecordedOrderService;
import com.oriah.order.service.RecordedPricing;
import com.oriah.order.service.RecordedReadService;
import com.oriah.order.service.RecordedOrderService.Who;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Recording a sale taken by phone, chat or paper for a caller with no account (Phase 9). SALES_AGENT, which the owner and the manager hold through their job
 * roles. The gateway's {@code /api/v1/admin/orders/recorded/**} matcher sends these to SALES_AGENT ahead of the ORDER_ADMIN prefix, and the capability is checked
 * here as well. A salesperson reads no landed cost or margin (Q29): every answer is an allow-list built for them, never an order administrator's view.
 */
@RestController
@RequestMapping("/api/v1/admin/orders/recorded")
@Tag(name = "Recorded orders", description = "Orders staff record for a caller with no account; SALES_AGENT")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking SALES_AGENT (or BACKDATE_SALE for a paper sale older than the window)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('SALES_AGENT')")
public class RecordedOrderController {

    private static final String BACKDATE_SALE = "ROLE_BACKDATE_SALE";

    private final RecordedOrderService service;
    private final RecordedReadService reads;
    private final RecordedPricing pricing;

    public RecordedOrderController(RecordedOrderService service, RecordedReadService reads, RecordedPricing pricing) {
        this.service = service;
        this.reads = reads;
        this.pricing = pricing;
    }

    private static Who who(Jwt jwt, Authentication auth) {
        return new Who(Actor.of(jwt), auth.getAuthorities().stream().anyMatch(a -> BACKDATE_SALE.equals(a.getAuthority())));
    }

    @Operation(operationId = "recordOrder", summary = "Record a sale for a caller with no account, and hold its pieces",
            description = "Staff say who is buying, what, how it goes and (for a paper sale) when; the server dates and prices everything itself (no price, discount or total can be "
                    + "typed) and the person confirms the total they read to the caller in `expectedTotal`. A repeat with the same `Idempotency-Key` and request is the same order "
                    + "(200); the pieces are held for ten minutes, and payment is the staff confirmation of money (`POST /api/v1/admin/payments/confirmations`).")
    @ApiResponse(responseCode = "201", description = "Recorded and held")
    @ApiResponse(responseCode = "200", description = "The same request was recorded before: that order")
    @ApiResponse(responseCode = "400", description = "A body that cannot be recorded; see fieldErrors or the problem type (invalid-contact, invalid-delivery, "
            + "delivery-reason-required, invalid-occurred-at, backdate-reason-required)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "idempotency-key-reused, total-differs (names the real total), zero-total-not-supported, no-price-at-date, "
            + "late-sale-delivery-must-be-agreed, delivery-place-not-offered, town-not-offered, insufficient-stock (names each short piece or box)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Catalog, shipping or inventory did not answer: nothing is saved, try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<RecordedOrderView> record(@AuthenticationPrincipal Jwt jwt, Authentication auth,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                    @Valid @RequestBody RecordOrderRequest request) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        RecordedOrderService.Recorded done = service.record(request, key, who(jwt, auth));
        return ResponseEntity.status(done.created() ? HttpStatus.CREATED : HttpStatus.OK).body(RecordedOrderView.of(done.order(), !done.created()));
    }

    @Operation(operationId = "previewRecordedOrder", summary = "Price a sale to read the total to the caller, writing and holding nothing",
            description = "The same pricing as recording, with no key, no hold and no personal data: no caller, no recipient, no address and no expected total.")
    @ApiResponse(responseCode = "200", description = "The sale as it would be recorded")
    @PostMapping("/preview")
    public RecordedViews.Preview preview(Authentication auth, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PreviewOrderRequest request) {
        RecordedOrderService.Previewed done = service.preview(request.asSale(), who(jwt, auth));
        return RecordedViews.Preview.of(done.priced(), done.occurredAt(), done.kind().name());
    }

    @Operation(operationId = "listRecordedOrders", summary = "Recorded orders, newest first",
            description = "`q` finds by the caller's phone in any spelling (exact), a receipt number, a slip number (exact) or a name (a prefix of three letters or more); "
                    + "`status` narrows. At most 50 a page. A phone is masked in the list.")
    @ApiResponse(responseCode = "200", description = "A page of recorded orders")
    @GetMapping
    public RecordedViews.Page list(@RequestParam(name = "q", required = false) String q, @RequestParam(name = "status", required = false) String status,
                                   @RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) {
        return reads.list(q, status, page, size);
    }

    @Operation(operationId = "getRecordedOrder", summary = "One recorded order",
            description = "The caller's phone and the parcel's address are shown while the order is alive and withheld once it is delivered. A web order is 404.")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "404", description = "No such recorded order (a web order is not one)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public RecordedOrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return reads.detail(id, Actor.of(jwt));
    }

    @Operation(operationId = "receiptOfRecordedOrder", summary = "The receipt to give the caller",
            description = "Ours, never a tax invoice, with no VAT line: the receipt number, the day it was paid, the caller's name only, every line and the total.")
    @ApiResponse(responseCode = "200", description = "The receipt")
    @ApiResponse(responseCode = "409", description = "receipt-not-ready: the order is not paid yet",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}/receipt")
    public RecordedViews.Receipt receipt(@PathVariable("id") UUID id) {
        return reads.receipt(id);
    }

    @Operation(operationId = "cancelRecordedOrder", summary = "Cancel a recorded order that is waiting for money",
            description = "Its hold goes and the recipient and address are forgotten; the caller is kept. A paid order is 409 (a paid sale is undone by a return, never a status change).")
    @ApiResponse(responseCode = "200", description = "The order, now CANCELLED")
    @ApiResponse(responseCode = "409", description = "The order cannot be cancelled now",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/cancel")
    public RecordedOrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return reads.cancel(id, Actor.of(jwt));
    }

    @Operation(operationId = "recordedBoxes", summary = "The boxes a sale can go out in",
            description = "Catalog's packaging, by name and SKU (no price): what a salesperson picks from when the product's own box has run out. Read with the person's token.")
    @ApiResponse(responseCode = "200", description = "The boxes")
    @ApiResponse(responseCode = "503", description = "Catalog did not answer",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/boxes")
    public java.util.List<com.oriah.order.client.CatalogLookupClient.Box> boxes() {
        return reads.boxes();
    }

    @Operation(operationId = "recordedDeliveryPlaces", summary = "Where Oriah can deliver and by what, with no price",
            description = "Shipping's own list of the towns and options that are switched on: what a salesperson picks from when a delivery is agreed by phone.")
    @ApiResponse(responseCode = "200", description = "The towns by province and the options")
    @GetMapping("/delivery-places")
    public ShippingClient.Places places() {
        return pricing.places();
    }
}
