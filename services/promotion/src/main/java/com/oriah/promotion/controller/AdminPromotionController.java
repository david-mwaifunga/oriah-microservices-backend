package com.oriah.promotion.controller;

import java.util.List;
import java.util.UUID;

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

import com.oriah.promotion.dto.PromotionRequest;
import com.oriah.promotion.service.PromotionService;
import com.oriah.promotion.service.PromotionService.PromotionView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Promotions, kept by admin (Phase 7): on one product, one category (with its sub-categories) or the whole shop, for Lusaka calendar days, at a
 * Kwacha amount or a percentage off each piece. They apply by themselves; nobody types a discount at a sale. CATALOG_ADMIN (the realm has no
 * PROMOTION_ADMIN role). A promotion is never edited: {@code replace} stops the old one and starts a new one that says which it replaces.
 */
@RestController
@RequestMapping("/api/v1/admin/promotions")
@Tag(name = "Promotions (admin)", description = "Promotions on a product, a category or the whole shop; CATALOG_ADMIN")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking CATALOG_ADMIN",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('CATALOG_ADMIN')")
public class AdminPromotionController {

    private static final String PROBLEMS = "application/problem+json";

    private final PromotionService promotions;

    public AdminPromotionController(PromotionService promotions) {
        this.promotions = promotions;
    }

    @Operation(operationId = "listPromotions", summary = "Promotions, newest first; optionally of one scope or only those in force now (true) or not (false)")
    @ApiResponse(responseCode = "200", description = "The promotions, newest first")
    @GetMapping
    public List<PromotionView> list(@RequestParam(name = "scopeType", required = false) String scopeType,
                                    @RequestParam(name = "inForce", required = false) Boolean inForce) {
        return promotions.list(scopeType, inForce);
    }

    @Operation(operationId = "getPromotion", summary = "One promotion")
    @ApiResponse(responseCode = "404", description = "promotion-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The promotion")
    @GetMapping("/{id}")
    public PromotionView get(@PathVariable("id") UUID id) {
        return promotions.get(id);
    }

    @Operation(operationId = "createPromotion", summary = "Make a promotion; it may not start in the past")
    @ApiResponse(responseCode = "201", description = "The promotion")
    @PostMapping
    public ResponseEntity<PromotionView> create(@Valid @RequestBody PromotionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promotions.create(request.name(), request.scopeType(), request.scopeId(),
                request.discountType(), request.amount(), request.startsOn(), request.endsOn(), actor(jwt)));
    }

    @Operation(operationId = "replacePromotion", summary = "Change a promotion: the old one stops now and the new one says which it replaces")
    @ApiResponse(responseCode = "201", description = "The new promotion")
    @ApiResponse(responseCode = "404", description = "promotion-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "already-stopped", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/replace")
    public ResponseEntity<PromotionView> replace(@PathVariable("id") UUID id, @Valid @RequestBody PromotionRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promotions.replace(id, request.name(), request.scopeType(), request.scopeId(),
                request.discountType(), request.amount(), request.startsOn(), request.endsOn(), actor(jwt)));
    }

    @Operation(operationId = "stopPromotion", summary = "End a promotion from now: it applied before this moment and does not after")
    @ApiResponse(responseCode = "404", description = "promotion-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "already-stopped", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The promotion, stopped from now")
    @PostMapping("/{id}/stop")
    public PromotionView stop(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        return promotions.stop(id, actor(jwt));
    }

    private static String actor(Jwt jwt) {
        return jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
    }
}
