package com.oriah.catalog.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.dto.CategoryCreateRequest;
import com.oriah.catalog.dto.CategoryResponse;
import com.oriah.catalog.dto.CategoryUpdateRequest;
import com.oriah.catalog.service.CategoryService;
import com.oriah.observability.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

/**
 * Shaping the storefront's taxonomy.
 *
 * <h2>Why this exists</h2>
 *
 * The category tree was readable and empty. {@code categories} and
 * {@code product_categories} are real tables with a parent hierarchy;
 * {@code Category} can rename, reparent and reposition, and refuses a cycle
 * the database CHECK cannot express; and the storefront list endpoint has
 * always accepted a {@code category} filter resolved to a slug and all its
 * descendants. Nothing could create a row, so {@code GET /api/v1/categories}
 * answered {@code []} forever and the filter had nothing to filter by. An
 * explicit taxonomy that cannot be populated is tags with extra steps.
 *
 * <h2>Admin-only, while the reads are anonymous</h2>
 *
 * The tree is storefront navigation, so reading it needs no token -- see
 * {@link CategoryController}. Writing it reshapes every shopper's view of the
 * catalogue, so it carries the same role that creates the products it
 * organises.
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
@Tag(name = "Admin categories", description = "Shaping the storefront taxonomy")
@PreAuthorize("hasRole('CATALOG_ADMIN')")
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
@Slf4j
public class AdminCategoryController {

    private final CategoryService service;

    public AdminCategoryController(CategoryService service) {
        this.service = service;
    }

    @Operation(operationId = "createCategory", summary = "Create a category",
            description = "The slug is derived from the name. A client-chosen slug is a "
                    + "client choosing a URL, and two would collide in a way only the "
                    + "unique index notices.")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the public read path")
    @ApiResponse(responseCode = "400",
            description = "Blank name, or a parentId naming no category; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<CategoryResponse> create(
            @Valid @RequestBody CategoryCreateRequest request) {
        log.debug("POST /api/v1/admin/categories name=\"{}\"", request.name());
        CategoryResponse created = service.create(request);
        // Points at the public read, like the product endpoints do.
        return ResponseEntity
                .created(URI.create("/api/v1/categories/" + created.slug()))
                .body(created);
    }

    @Operation(operationId = "updateCategory", summary = "Rename, move or reposition a category",
            description = "Partial: null fields are left alone. Renaming does NOT change the "
                    + "slug -- it is in storefront URLs and in anyone's bookmarks, so the "
                    + "label and the address are separate concerns.")
    @ApiResponse(responseCode = "200", description = "The updated category")
    @ApiResponse(responseCode = "400",
            description = "A move that would create a cycle, or a parentId naming no category",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping("/{id}")
    public CategoryResponse update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return service.update(id, request)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", id.toString()));
    }

    @Operation(operationId = "deleteCategory", summary = "Remove a category",
            description = "Child categories move up to this one's parent and products keep "
                    + "existing -- only the membership links go. A taxonomy edit must never "
                    + "be able to delete something sellable.")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        if (!service.delete(id)) {
            throw new ResourceNotFoundException("Category", "id", id.toString());
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
