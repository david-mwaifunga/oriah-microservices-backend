package com.oriah.catalog.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.constants.CatalogConstants;
import com.oriah.catalog.dto.BelowCostPage;
import com.oriah.catalog.dto.PriceChangeResponse;
import com.oriah.catalog.dto.SetPriceRequest;
import com.oriah.catalog.dto.VariantPricingView;
import com.oriah.catalog.pricing.PriceAssessment;
import com.oriah.catalog.service.PricingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Pricing: setting a variant's price against what the stock cost, and seeing where every price stands.
 *
 * <p>CATALOG_ADMIN and COST_VIEWER both, here and at the gateway: the pricing screen shows landed cost, and cost is gated at the API.
 * The warning never refuses a price; it makes sure a price below cost is never saved unseen.
 */
@RestController
@RequestMapping(CatalogConstants.PRICING_BASE)
@PreAuthorize("hasRole('" + CatalogConstants.ROLE_CATALOG_ADMIN + "') and hasRole('" + CatalogConstants.ROLE_COST_VIEWER + "')")
@Tag(name = "Pricing (admin)", description = "Dated prices guarded against landed cost; CATALOG_ADMIN and COST_VIEWER")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking CATALOG_ADMIN or COST_VIEWER",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class PricingController {

    private static final int NAME_LIMIT = 100;

    private final PricingService pricing;

    public PricingController(PricingService pricing) {
        this.pricing = pricing;
    }

    @Operation(operationId = "setVariantPrice",
            summary = "Set a variant's price, compared with what the stock cost",
            description = "Adds a row of dated history and overwrites nothing. A price below the dearest unit cost still held (plus the "
                    + "dearest held cost of the product's default box) is a 409 `below-landed-cost` carrying every figure; saving it "
                    + "needs `belowCostReason` and the `acknowledgedFloor` the person saw. Nothing is saved without the cost having "
                    + "been looked at: if stock cannot be asked the answer is 503. Prices are in ZMW. `expectedPriceId` is the id of "
                    + "the price the person saw in force (null: unpriced); a price saved in between is `stale-price`.")
    @ApiResponse(responseCode = "200", description = "Saved")
    @ApiResponse(responseCode = "400", description = "Not valid: a currency other than ZMW, a price that is already the price, a date "
            + "in the future, too old or before the price it follows, or a malformed body",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such variant, or its product was deleted",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "below-landed-cost (with the figures), stale-price, concurrent-update, or "
            + "packaging-is-not-priced",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Stock refused to say what it cost (a fault between the services)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Stock could not be asked what it cost, so nothing was saved; Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/variants/{variantId}/price")
    public PriceChangeResponse setPrice(@PathVariable("variantId") UUID variantId, @Valid @RequestBody SetPriceRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return pricing.setPrice(variantId, request, staff(jwt), bearer(jwt));
    }

    @Operation(operationId = "getVariantPricing",
            summary = "A variant's price, its dated history, the cost it stands against and a suggestion",
            description = "The cost is read from stock afresh. `targetProfitPercent` (0 to 1000, at most two decimals) asks for a suggested "
                    + "price of the landed cost plus that percentage of it; without it the configured percentage is used, and if there is "
                    + "none there is no suggestion. A suggestion is never applied.")
    @ApiResponse(responseCode = "200", description = "The pricing view")
    @ApiResponse(responseCode = "404", description = "No such variant, or its product was deleted",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Stock could not be asked what it cost; Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/variants/{variantId}")
    public VariantPricingView view(@PathVariable("variantId") UUID variantId,
                                   @RequestParam(name = "targetProfitPercent", required = false) BigDecimal targetProfitPercent,
                                   @AuthenticationPrincipal Jwt jwt) {
        return pricing.view(variantId, targetProfitPercent, bearer(jwt));
    }

    @Operation(operationId = "listPricesAgainstCost",
            summary = "The priced pieces that stand below cost (or with no cost known, or in the wrong currency), judged on what a customer pays",
            description = "Every priced, active variant of a sellable product that has not been deleted, compared with the stock held NOW, so a "
                    + "dearer lot received, a corrected cost, a dear lot sold out of or a changed default box shows here the next time "
                    + "anyone looks. The comparison is made on the NET price: the list price (`price`) less the promotion in force "
                    + "(`discountPerPiece`, `promotionId`, `netPrice`). `at` (now or later) applies the promotions that will be in force "
                    + "then, which is how an admin sees what a promotion that has not started yet will do; `promotionId` lists only the "
                    + "pieces that promotion discounts (and the counts are about those pieces). `status` filters (BELOW by default; "
                    + "NO_COST_KNOWN, CURRENCY_MISMATCH, OK, or ALL); `counts` gives every status whatever the status filter. Biggest "
                    + "shortfall first. A promotion never needs a reason and is never refused: it is warned about here.")
    @ApiResponse(responseCode = "200", description = "A page of priced variants, possibly empty")
    @ApiResponse(responseCode = "400", description = "A status that does not exist, an invalid page or size, or an `at` that has passed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Stock could not be asked what it cost, or promotion could not be asked what applies; Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/below-cost")
    public BelowCostPage belowCost(@RequestParam(name = "status", defaultValue = "BELOW") String status,
                                   @RequestParam(name = "page", defaultValue = "0") int page,
                                   @RequestParam(name = "size", defaultValue = "" + CatalogConstants.DEFAULT_PAGE_SIZE) int size,
                                   @RequestParam(name = "at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at,
                                   @RequestParam(name = "promotionId", required = false) UUID promotionId,
                                   @AuthenticationPrincipal Jwt jwt) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > CatalogConstants.ADMIN_MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + CatalogConstants.ADMIN_MAX_PAGE_SIZE + ", was " + size);
        }
        PriceAssessment filter;
        if ("ALL".equalsIgnoreCase(status)) {
            filter = null;
        } else {
            try {
                filter = PriceAssessment.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status must be BELOW, NO_COST_KNOWN, CURRENCY_MISMATCH, OK or ALL, was '" + status + "'");
            }
        }
        return pricing.belowCost(filter, page, size, bearer(jwt), at, promotionId);
    }

    /** The caller's own token, read here on the request thread and handed on: inventory decides whether this person may read cost. */
    private static String bearer(Jwt jwt) {
        return "Bearer " + jwt.getTokenValue();
    }

    /** The subject is the record; the name is whatever the token carries (the `name` claim only), trimmed and capped, or null. */
    private static PricingService.Staff staff(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null) {
            name = name.strip();
            if (name.isEmpty()) {
                name = null;
            } else if (name.length() > NAME_LIMIT) {
                name = name.substring(0, NAME_LIMIT);
            }
        }
        return new PricingService.Staff("user:" + jwt.getSubject(), name);
    }
}
