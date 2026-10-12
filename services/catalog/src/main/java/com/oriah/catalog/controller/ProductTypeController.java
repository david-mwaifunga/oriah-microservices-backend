package com.oriah.catalog.controller;

import java.util.Comparator;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.dto.ProductTypeResponse;
import com.oriah.catalog.dto.ProductTypeResponse.AttributeDefinitionResponse;
import com.oriah.catalog.model.AttributeDefinition;
import com.oriah.catalog.model.ProductType;
import com.oriah.catalog.model.ProductTypeRegistry;
import com.oriah.catalog.repository.ProductTypeRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The product types and the spec fields each one declares.
 *
 * <h2>Why this endpoint exists</h2>
 *
 * The product detail page renders a static spec table — literal markup
 * reading "Solid 18k gold", "In our atelier", "30 days, unworn" — for every
 * product, so a watch page asserts the watch is solid 18k gold. Publishing the
 * type definitions lets the PDP render what the product actually has, and lets
 * the admin form render the matching inputs, from the same source that
 * performs the server-side validation.
 *
 * <h2>Where each half of the answer comes from</h2>
 *
 * The display name and ordering come from the {@code product_types} ROWS; the
 * attribute definitions come from {@link ProductTypeRegistry} in Java. That
 * split is the design, so this endpoint reads both rather than restating
 * either — an earlier draft hardcoded the display names here, which would have
 * made a third copy of something the table already owns and let the API drift
 * from the admin dropdown reading the same table.
 *
 * <p>The two halves cannot disagree about which codes exist, because
 * {@code ProductTypeConsistencyVerifier} fails the application context at
 * startup if they do. That is what lets this method assume
 * {@code definitionsFor} will not throw for a row it finds.
 *
 * <p>Read-only: adding a type means adding its definitions in Java, so it is a
 * deploy, not a form submission.
 */
@RestController
@RequestMapping("/api/v1/product-types")
@Tag(name = "Product types", description = "Type-specific attribute definitions")
@Slf4j
public class ProductTypeController {

    private final ProductTypeRepository types;

    public ProductTypeController(ProductTypeRepository types) {
        this.types = types;
    }

    @GetMapping
    @Operation(operationId = "listProductTypes",
            summary = "Product types with their declared attributes, in display order")
    @ApiResponse(responseCode = "200", description = "Every known product type")
    public List<ProductTypeResponse> list() {
        log.debug("GET /api/v1/product-types");
        return types.findAll().stream()
                .sorted(Comparator.comparingInt(ProductType::position)
                        .thenComparing(ProductType::code))
                .map(this::toResponse)
                .toList();
    }

    private ProductTypeResponse toResponse(ProductType type) {
        List<AttributeDefinitionResponse> attributes =
                ProductTypeRegistry.definitionsFor(type.code()).stream()
                        .map(ProductTypeController::toAttribute)
                        .toList();

        return new ProductTypeResponse(
                type.code(),
                type.displayName(),
                type.position(),
                attributes);
    }

    private static AttributeDefinitionResponse toAttribute(AttributeDefinition definition) {
        return new AttributeDefinitionResponse(
                definition.key(),
                definition.label(),
                definition.kind().name(),
                definition.required(),
                definition.unit(),
                definition.allowedValues());
    }
}
