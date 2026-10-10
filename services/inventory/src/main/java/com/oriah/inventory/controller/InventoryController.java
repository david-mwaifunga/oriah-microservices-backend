package com.oriah.inventory.controller;

import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.dto.InventoryResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.inventory.service.InventoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.extern.slf4j.Slf4j;

/**
 * Public read endpoints for inventory. No auth required — the cart and
 * PDP screens hit this when rendering "in stock" / "out of stock" /
 * "few left" badges.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Public stock reads")
@ApiResponses({
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
@Slf4j
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @Operation(operationId = "getByVariantId", summary = "Stock for one variant")
    @ApiResponse(responseCode = "200", description = "Stock levels for the variant")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{variantId}")
    public InventoryResponse getByVariantId(
            @PathVariable("variantId") UUID variantId) {
        log.debug("GET /api/v1/inventory/{}", variantId);
        return service.findByVariantId(variantId)
                .map(InventoryResponse::from)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Inventory", "variantId", variantId.toString()));
    }
}
