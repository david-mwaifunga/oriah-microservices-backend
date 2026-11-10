package com.oriah.procurement.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.oriah.procurement.dto.CreateSupplierRequest;
import com.oriah.procurement.dto.SupplierResponse;
import com.oriah.procurement.service.SupplierService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The supplier list Oriah buys from, for staff who hold the procurement
 * capability. Only the people who place purchase orders touch it.
 *
 * <p>{@code @PreAuthorize} is on the class: the gateway already requires the
 * capability for this prefix, and the service requires it again, because services
 * call each other directly and the ports are only closed in production.
 */
@RestController
@RequestMapping("/api/v1/admin/procurement/suppliers")
@PreAuthorize("hasRole('PROCUREMENT_ADMIN')")
@Tag(name = "Suppliers", description = "Who Oriah buys from")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the procurement capability",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class SupplierController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @Operation(operationId = "createSupplier", summary = "Add a supplier")
    @ApiResponse(responseCode = "201", description = "The supplier was added; Location points at it")
    @ApiResponse(responseCode = "400", description = "A field is missing or breaks a rule; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A live supplier already has this name (letter case ignored)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody CreateSupplierRequest request) {
        SupplierResponse created = SupplierResponse.from(service.create(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(operationId = "listSuppliers",
            summary = "The suppliers, by name, optionally only those in use or those stopped, or matching some text")
    @ApiResponse(responseCode = "200", description = "A page of suppliers")
    @ApiResponse(responseCode = "400",
            description = "size outside 1-100, a negative page, a malformed active, or a sort parameter "
                    + "(this endpoint is fixed to name order)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public Page<SupplierResponse> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @Parameter(description = "true: only suppliers in use; false: only those we have stopped using; absent: both")
            @RequestParam(name = "active", required = false) Boolean active,
            @Parameter(description = "Names containing this text, ignoring letter case. % and _ are ordinary characters.")
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "sort", required = false) String sort) {

        // IllegalArgumentException is what the shared advice renders as a 400
        // RFC 7807 body, so every refusal below arrives in the platform's shape.
        // Refused, not clamped or ignored: a quietly changed request hides the
        // caller's mistake.
        if (sort != null) {
            throw new IllegalArgumentException("This endpoint is always ordered by name, ignoring letter case, "
                    + "and does not accept a 'sort' parameter. Remove it.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater, was " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ", was " + size);
        }
        return service.list(page, size, active, q).map(SupplierResponse::from);
    }

    @Operation(operationId = "getSupplier", summary = "One supplier")
    @ApiResponse(responseCode = "200", description = "The supplier")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such supplier (a deleted one counts as none)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public SupplierResponse get(@PathVariable UUID id) {
        return SupplierResponse.from(service.get(id));
    }

    @Operation(operationId = "deleteSupplier",
            summary = "Correct a mistake: delete a supplier that should never have been added",
            description = "Frees the name. To stop buying from a real supplier, switch it inactive instead.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such supplier, or already deleted",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
