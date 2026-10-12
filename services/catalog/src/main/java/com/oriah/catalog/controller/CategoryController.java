package com.oriah.catalog.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.dto.CategoryResponse;
import com.oriah.catalog.service.CategoryService;
import com.oriah.observability.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Public reads over the category tree.
 *
 * <p>Anonymous, like the rest of the storefront catalogue. Note that adding
 * these paths to the gateway's PUBLIC_GET_PATHS is NOT sufficient on its own:
 * catalog's own SecurityConfig ends in {@code anyRequest().authenticated()},
 * so it has to permit them too, and a test hits this service directly and
 * anonymously to prove both halves are in place.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Storefront navigation taxonomy")
@Slf4j
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    /**
     * The whole tree in one request.
     *
     * <p>Nested rather than flat because the alternative is one request per
     * level, and the navigation needs all of it to render anything. The tree
     * is small and highly cacheable, which is what makes shipping it whole
     * reasonable.
     */
    @GetMapping
    @Operation(operationId = "listCategories",
            summary = "The category tree, roots first, each with its children nested")
    @ApiResponse(responseCode = "200", description = "The tree, possibly empty")
    public List<CategoryResponse> tree() {
        log.debug("GET /api/v1/categories");
        return service.tree();
    }

    /**
     * One category by slug, without its children.
     *
     * <p>Keyed on the slug rather than the id because that is what appears in
     * a storefront URL -- the id is of no use to a client that arrived from a
     * link.
     */
    @GetMapping("/{slug}")
    @Operation(operationId = "getCategoryBySlug", summary = "One category by its slug")
    @ApiResponse(responseCode = "200", description = "The category")
    @ApiResponse(responseCode = "404", description = "No category with that slug",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public CategoryResponse bySlug(@PathVariable("slug") String slug) {
        log.debug("GET /api/v1/categories/{}", slug);
        return service.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "slug", slug));
    }
}
