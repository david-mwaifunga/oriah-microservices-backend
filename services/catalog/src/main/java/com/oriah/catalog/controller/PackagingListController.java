package com.oriah.catalog.controller;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.constants.CatalogConstants;
import com.oriah.catalog.service.PackagingListService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The boxes a salesperson can choose from when a sale is recorded (Phase 9; CLIENT_DECISIONS 4.2: the box is proposed from the product's default and the person
 * picks another when it has run out). Identity only: the packaging in use, by name and SKU, never a price and never a cost (Q29).
 *
 * <h2>Internal, by where it is NOT routed</h2>
 *
 * The order service asks directly, by service name, forwarding the signed-in person's own token (SALES_AGENT, who chooses; FULFILMENT_AGENT, who packs;
 * CATALOG_ADMIN, who manages the catalogue). The gateway has no route for {@code /api/v1/admin/variants/**} and denies unmatched admin paths, so a browser
 * cannot reach it.
 */
@RestController
@RequestMapping("/api/v1/admin/variants")
@PreAuthorize("hasAnyRole('" + CatalogConstants.ROLE_SALES_AGENT + "', '" + CatalogConstants.ROLE_FULFILMENT_AGENT + "', '" + CatalogConstants.ROLE_CATALOG_ADMIN + "')")
@Tag(name = "Packaging list", description = "Internal: the boxes a sale can go out in")
public class PackagingListController {

    private final PackagingListService service;

    public PackagingListController(PackagingListService service) {
        this.service = service;
    }

    @GetMapping("/packaging")
    @Operation(operationId = "listPackaging", summary = "The packaging a sale can go out in",
            description = "Internal. Every active variant of a packaging product that is not deleted, by name and SKU. Identity only, never a price or a cost.")
    @ApiResponse(responseCode = "200", description = "The boxes, by name")
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "None of SALES_AGENT, FULFILMENT_AGENT or CATALOG_ADMIN",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<PackagingListService.BoxEntry> packaging() {
        return service.boxes();
    }
}
