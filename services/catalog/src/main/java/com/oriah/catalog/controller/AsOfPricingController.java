package com.oriah.catalog.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.dto.AsOfPricingRequest;
import com.oriah.catalog.dto.AsOfPricingResponse;
import com.oriah.catalog.service.AsOfPricingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * What a piece cost at a past instant, for a sale keyed after the day it was made (Phase 7: phone and paper sales).
 *
 * <h2>Internal, by where it is NOT routed</h2>
 *
 * The order service reaches this directly, by service name, forwarding the signed-in person's own token; the gateway has no route for
 * {@code /api/v1/admin/variants/**} and denies unmatched admin paths, so a browser cannot reach it. Open to SALES_AGENT, the capability that
 * records a sale. It is the staff twin of the storefront's {@code POST /api/v1/variants/pricing}: that one prices NOW and only what is on sale;
 * this one prices at the sale's true date and ignores today's publication state.
 */
@RestController
@RequestMapping("/api/v1/admin/variants")
@PreAuthorize("hasRole('SALES_AGENT')")
@Tag(name = "Pricing as of a date", description = "Internal: prices a sale at its true date")
@Slf4j
public class AsOfPricingController {

    private final AsOfPricingService service;

    public AsOfPricingController(AsOfPricingService service) {
        this.service = service;
    }

    @PostMapping("/pricing-as-of")
    @Operation(operationId = "priceVariantsAsOf",
            summary = "Price up to 100 variants at a past instant",
            description = "Internal. Read-only despite being a POST. Each requested id is answered, in request order, with the list price in force "
                    + "at the instant, the promotion that applied then and the net price. A piece with no price yet at that instant is "
                    + "NO_PRICE_AT_DATE (never zero, never today's price); a packaging entry or an unknown id is UNKNOWN. Today's publication "
                    + "state is ignored: a piece withdrawn since was still sold at its price on the day.")
    @ApiResponse(responseCode = "200", description = "One entry per requested id, in order")
    @ApiResponse(responseCode = "400", description = "No instant, an instant in the future, an empty list, or more than 100 ids",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Lacking SALES_AGENT",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Promotion could not be asked what applied: nothing is priced, never a guess",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public AsOfPricingResponse priceAsOf(@Valid @RequestBody AsOfPricingRequest request) {
        log.debug("POST /api/v1/admin/variants/pricing-as-of ids={}", request.variantIds().size());
        return service.priceAt(request.at(), request.variantIds());
    }
}
