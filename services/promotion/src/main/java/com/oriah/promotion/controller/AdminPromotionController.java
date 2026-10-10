package com.oriah.promotion.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.promotion.dto.CreatePromotionRequest;
import com.oriah.promotion.dto.PromotionResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.promotion.model.Promotion;
import com.oriah.promotion.service.PromotionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

/**
 * Admin-side promotion management. Reuses CATALOG_ADMIN role for the
 * marketing/catalog team (we don't have a dedicated PROMOTION_ADMIN
 * role — too granular for the demo scope).
 */
@RestController
@RequestMapping("/api/v1/admin/promotions")
@Tag(name = "Promotions (admin)", description = "Discount code writes, CATALOG_ADMIN only")
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
@PreAuthorize("hasRole('CATALOG_ADMIN')")
public class AdminPromotionController {

    private final PromotionService service;

    public AdminPromotionController(PromotionService service) {
        this.service = service;
    }

    @Operation(operationId = "create", summary = "Create a discount code")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the code")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<PromotionResponse> create(@Valid @RequestBody CreatePromotionRequest body) {
        Promotion p = service.create(body);
        return ResponseEntity.created(java.net.URI.create("/api/v1/admin/promotions/" + p.id()))
                .body(PromotionResponse.from(p));
    }

    @Operation(operationId = "list", summary = "All discount codes, paged")
    @ApiResponse(responseCode = "200", description = "A page of codes")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<PromotionResponse>> list(@PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(service.findAll(pageable).map(PromotionResponse::from));
    }

    @Operation(operationId = "disable", summary = "Disable a discount code")
    @ApiResponse(responseCode = "200", description = "The disabled code")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{code}/disable")
    public PromotionResponse disable(@PathVariable("code") String code) {
        return service.disable(code)
                .map(PromotionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", "code", code));
    }
}
