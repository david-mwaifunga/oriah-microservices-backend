package com.oriah.catalog.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.constants.CatalogConstants;
import com.oriah.catalog.dto.VariantLookupRequest;
import com.oriah.catalog.dto.VariantLookupResponse;
import com.oriah.catalog.service.VariantLookupService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Names variants for procurement (and for the catalogue's own admins).
 *
 * <h2>Internal, by where it is NOT routed</h2>
 *
 * Procurement reaches this directly, by service name, forwarding the signed-in
 * person's own token. The gateway has no route for {@code /api/v1/admin/variants/**}
 * (and denies unmatched admin paths), so a browser cannot reach it. It is open to
 * CATALOG_ADMIN, who manage the catalogue, to PROCUREMENT_ADMIN, who must be
 * able to resolve what they are ordering, and (Phase 8) to FULFILMENT_AGENT: the order system asks with the packer's own token what a box is
 * called, so a packing slip can name it; forwarding the caller's token means no
 * new machine role and no realm change. It returns identity only, never a price.
 *
 * <p>POST because the id list is too long for a URL; it changes nothing.
 */
@RestController
@RequestMapping("/api/v1/admin/variants")
@PreAuthorize("hasAnyRole('" + CatalogConstants.ROLE_CATALOG_ADMIN + "', '" + CatalogConstants.ROLE_PROCUREMENT_ADMIN + "', '"
        + CatalogConstants.ROLE_FULFILMENT_AGENT + "')")
@Tag(name = "Variant lookup", description = "Internal: names variants for procurement")
@Slf4j
public class VariantLookupController {

    private final VariantLookupService service;

    public VariantLookupController(VariantLookupService service) {
        this.service = service;
    }

    @PostMapping("/lookup")
    @Operation(operationId = "lookupVariants",
            summary = "Name up to 100 variants",
            description = "Internal. Read-only despite being a POST. One entry per requested id, "
                    + "in request order: found = false for an id that is no variant. A deleted "
                    + "product's variant is found and flagged productDeleted. Identity only, "
                    + "never a price.")
    @ApiResponse(responseCode = "200", description = "One entry per requested id, in order")
    @ApiResponse(responseCode = "400", description = "Empty list, or more than 100 ids",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "None of CATALOG_ADMIN, PROCUREMENT_ADMIN or FULFILMENT_AGENT",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public VariantLookupResponse lookup(@Valid @RequestBody VariantLookupRequest request) {
        log.debug("POST /api/v1/admin/variants/lookup ids={}", request.variantIds().size());
        return service.lookup(request.variantIds());
    }
}
