package com.oriah.catalog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.dto.VariantPricingRequest;
import com.oriah.catalog.dto.VariantPricingResponse;
import com.oriah.catalog.service.VariantPricingService;

import lombok.extern.slf4j.Slf4j;

/**
 * What the catalogue says a set of variants costs.
 *
 * <h2>This endpoint is the point of the whole products phase</h2>
 *
 * cart-service has no catalogue dependency: it takes {@code unitPrice} from
 * the request body and stores it. Demonstrated against the running stack --
 * an item added at {@code unitPrice: 0.01} produced a cart subtotal of 0.01
 * and a checkout preview total of 5.01, so anything could be bought for a
 * cent. Cart, checkout preview and checkout confirm all resolve prices through
 * here instead.
 *
 * <h2>Why a POST for a read</h2>
 *
 * The identifier list exceeds a sane URL length well before its cap, so this
 * cannot be a GET with a repeated query parameter. The operation mutates
 * nothing, which is stated on the operation itself so the verb is not mistaken
 * for a write. The request is capped at 100 ids, matching the cap cart
 * enforces on lines per cart -- a bag that cannot be priced in one request
 * cannot exist, and an unbounded list would let one request scan the variant
 * table.
 *
 * <h2>Anonymous, like the rest of the storefront catalogue</h2>
 *
 * Prices are public -- they are on the product page. Being anonymous is also
 * why the service logs a COUNT of unresolved ids rather than the ids
 * themselves: a log line naming them would turn a pricing lookup into a
 * record of what an unidentified visitor has in their bag.
 */
@RestController
@RequestMapping("/api/v1/variants")
@Tag(name = "Variant pricing", description = "The catalogue as the authority on price")
@Slf4j
public class VariantPricingController {

    private final VariantPricingService service;

    public VariantPricingController(VariantPricingService service) {
        this.service = service;
    }

    @PostMapping("/pricing")
    @Operation(operationId = "priceVariants",
            summary = "Resolve prices for up to 100 variants",
            description = "Read-only despite being a POST: the identifier list is too "
                    + "long for a URL. Each requested id is answered with a status of "
                    + "PRICED, UNPUBLISHED or UNKNOWN, in request order. An UNPUBLISHED "
                    + "entry deliberately carries no price, so a caller that skips the "
                    + "status check fails loudly rather than charging for something that "
                    + "is not for sale.")
    @ApiResponse(responseCode = "200", description = "One entry per requested id, in order")
    @ApiResponse(responseCode = "400", description = "Empty list, or more than 100 ids",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public VariantPricingResponse price(@Valid @RequestBody VariantPricingRequest request) {
        log.debug("POST /api/v1/variants/pricing ids={}", request.variantIds().size());
        return service.priceAll(request.variantIds());
    }
}
