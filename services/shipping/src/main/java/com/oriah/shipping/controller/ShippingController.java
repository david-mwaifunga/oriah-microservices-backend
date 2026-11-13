package com.oriah.shipping.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.shipping.dto.QuoteRequest;
import com.oriah.shipping.service.DeliveryOfferService;
import com.oriah.shipping.service.DeliveryOfferService.Offers;
import com.oriah.shipping.service.DeliveryOfferService.Quote;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/shipping")
@Tag(name = "Shipping", description = "What delivery is offered and what it costs, before paying. Zambia only; priced per town by admin")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the required role",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('USER')")
public class ShippingController {

    private final DeliveryOfferService offers;

    public ShippingController(DeliveryOfferService offers) {
        this.offers = offers;
    }

    @Operation(operationId = "offeredTowns", summary = "Where Oriah delivers and what it costs: provinces, their towns, and each town's options with a published price",
            description = "Only a town that is switched on, with an option that is switched on and a PUBLISHED price, is listed. Self-collect is always offered at no "
                    + "charge. The free-delivery threshold is shown when it is on.")
    @ApiResponse(responseCode = "200", description = "What is offered")
    @GetMapping("/towns")
    public ResponseEntity<Offers> towns() {
        return ResponseEntity.ok(offers.offers());
    }

    @Operation(operationId = "quote", summary = "The delivery price for one town and option, before paying",
            description = "The amount is what goes on the order. The recipient paying the courier means K0; the free-delivery threshold is judged on the goods "
                    + "after discount. A town not yet priced is `town-not-offered` (the shop prices it first), never `outside the delivery area`.")
    @ApiResponse(responseCode = "200", description = "The quote, naming the published price it used")
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "town-not-found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "town-not-offered, payer-not-offered",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/quote")
    public ResponseEntity<Quote> quote(@Valid @RequestBody QuoteRequest body) {
        return ResponseEntity.ok(offers.quote(body.townId(), body.optionCode(), body.payer(), body.goodsTotal(), body.currency()));
    }
}
