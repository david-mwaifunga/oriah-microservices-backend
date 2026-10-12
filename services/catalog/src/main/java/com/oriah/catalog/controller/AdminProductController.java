package com.oriah.catalog.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.access.prepost.PreAuthorize;

import com.oriah.catalog.constants.CatalogConstants;
import com.oriah.catalog.dto.ProductCreateRequest;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.observability.ResourceNotFoundException;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import com.oriah.catalog.dto.ProductImageResponse;
import com.oriah.catalog.service.ProductImageService;
import com.oriah.catalog.service.ServedImage;
import com.oriah.catalog.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin-facing endpoints for the catalog domain.
 *
 * <p>Mounted under a separate path prefix from {@link ProductController} so
 * different security and rate-limit policies apply at the gateway and at the
 * controller level. The controller-level half is the {@code @PreAuthorize}
 * below -- the javadoc here used to say it would arrive "when iteration 7 adds
 * Keycloak", which it did, some time ago.
 *
 * <p>The role name comes from {@link CatalogConstants#ROLE_CATALOG_ADMIN}
 * rather than a literal, so the one string a typo would silently break -- a
 * role check against a name the realm does not declare fails CLOSED, leaving
 * the endpoint uncallable with no error anywhere -- has a single definition
 * that {@code scripts/validate_roles.py} checks against the realm file.
 *
 * <h2>Logging</h2>
 * Inbound mutations are logged at <b>DEBUG</b> here — the matching INFO
 * line lands in {@link ProductService}, which carries the canonical "who
 * changed what" record. Logging twice would duplicate every event.
 */
@RestController
@RequestMapping(CatalogConstants.ADMIN_BASE)
@PreAuthorize("hasRole('" + CatalogConstants.ROLE_CATALOG_ADMIN + "')")
@Tag(name = "Products (admin)", description = "Catalogue writes, CATALOG_ADMIN only")
@ApiResponses({
        // The tail every operation on this controller shares. springdoc merges
        // class-level responses into each one, so 401/403/500 are stated once
        // rather than three times.
        //
        // 403 is declared here and NOT on ProductController, because that is
        // the real difference between the two: these endpoints are behind
        // @PreAuthorize("hasRole('CATALOG_ADMIN')"), so a valid token with the
        // wrong role is a documented outcome. Spring Security's
        // AccessDeniedException reaches the shared advice, which renders it as
        // a problem body like everything else.
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking CATALOG_ADMIN",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
@Slf4j
public class AdminProductController {

    private final ProductService service;
    private final ProductImageService imageService;

    public AdminProductController(ProductService service, ProductImageService imageService) {
        this.service = service;
        this.imageService = imageService;
    }

    /**
     * Creates a product.
     *
     * <p>Documented as <strong>201</strong>, which is what it returns. The spec
     * said {@code 200} for the whole life of this endpoint: springdoc cannot
     * see inside {@code ResponseEntity.created(...)} and falls back to 200, and
     * nothing checked. Eleven endpoints across the platform were wrong this
     * way.
     *
     * <p>The {@code Location} header is documented too — it is the only thing
     * telling a caller where the new resource lives, and it points at the
     * PUBLIC read path rather than this admin one.
     */
    @PostMapping
    @Operation(operationId = "create", summary = "Create a product")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the public read path",
            headers = @io.swagger.v3.oas.annotations.headers.Header(
                    name = "Location", description = "/api/v1/products/{id}",
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        log.debug("POST /api/v1/admin/products title=\"{}\"", request.title());
        ProductResponse saved = service.create(request.title(), request.description(), request.tags());
        // Location points at the public read endpoint, not the admin path.
        URI location = URI.create("/api/v1/products/" + saved.id());
        return ResponseEntity.created(location).body(saved);
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "update", summary = "Partially update a product")
    @ApiResponse(responseCode = "200", description = "The updated product")
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    public ProductResponse update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ProductUpdateRequest request) {
        log.debug("PATCH /api/v1/admin/products/{}", id);
        return service.update(id, request)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
    }

    /**
     * 204 on success. The {@code ResponseEntity} stays here, unlike on the
     * other two handlers: 204 is not the default status for a {@code void}
     * handler (Spring would answer 200 with an empty body), and
     * {@code @ResponseStatus(NO_CONTENT)} would move the status away from the
     * line that produces it.
     */
    /**
     * Read a product as an operator, including drafts.
     *
     * <p>The public endpoint deliberately 404s anything not ACTIVE, so this is
     * the only way to see a product being worked on.
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getForAdmin",
            summary = "One product by id, including DRAFT and ARCHIVED")
    @ApiResponse(responseCode = "200", description = "The product, whatever its status")
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ProductResponse getForAdmin(@PathVariable("id") UUID id) {
        log.debug("GET /api/v1/admin/products/{}", id);
        return service.findByIdForAdmin(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
    }

    /**
     * Add a purchasable configuration.
     *
     * <p>201 with the full product rather than just the variant: an admin
     * screen needs the product's recomputed price range and publishability
     * immediately after the write, and returning only the variant would make
     * it fetch the product again to find out whether it can now be published.
     */
    @PostMapping("/{id}/variants")
    @Operation(operationId = "addVariant", summary = "Add a variant to a product")
    @ApiResponse(responseCode = "201", description = "The product, with the new variant")
    @ApiResponse(responseCode = "400",
            description = "Validation failure: option keys not matching the product's "
                    + "declared axes, a duplicate option combination, a currency differing "
                    + "from the product's other variants, or a blank SKU",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProductResponse> addVariant(
            @PathVariable("id") UUID id,
            @Valid @RequestBody VariantCreateRequest request) {
        log.debug("POST /api/v1/admin/products/{}/variants sku=\"{}\"", id, request.sku());
        ProductResponse updated = service.addVariant(id, request)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(updated);
    }

    /**
     * Publish a product to the storefront.
     *
     * <p>409 rather than 400 when it is not publishable: the request is
     * perfectly well formed, and what is wrong is the product's state -- it has
     * no variant priced above zero. A 400 would tell an operator to fix their
     * request when what they need to fix is the price.
     */
    @PostMapping("/{id}/publish")
    @Operation(operationId = "publishProduct",
            summary = "Publish a product, if it has an active variant priced above zero")
    @ApiResponse(responseCode = "200", description = "The published product")
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "The product has no active variant priced above zero",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ProductResponse publish(@PathVariable("id") UUID id) {
        log.debug("POST /api/v1/admin/products/{}/publish", id);
        return service.publish(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
    }

    /**
     * Upload a product image.
     *
     * <p>Multipart, and the only endpoint in the platform that accepts one.
     * The {@code Content-Type} and filename the client sends are recorded in
     * the error message and used for nothing else: the ingest path identifies
     * the format from the bytes, re-encodes them, and derives the stored
     * content type from the decode. A client assertion about file contents is
     * not evidence.
     *
     * <p>Returns the image reference rather than the whole product, because an
     * admin gallery adds one image at a time and wants its URL immediately.
     *
     * <p>201 even when the image already existed. A re-upload of identical
     * bytes is idempotent and returns the original id, and answering 200 in
     * that case would make the admin UI branch on something it cannot act on
     * differently.
     */
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadProductImage",
            summary = "Upload an image for a product",
            description = "JPEG, PNG or WebP, up to 5 MB. The bytes are re-encoded: EXIF "
                    + "is applied and then stripped (so a supplier's GPS coordinates are "
                    + "not published and the photograph is not left sideways), and the "
                    + "stored content type comes from decoding the file rather than from "
                    + "the request. Uploading the same bytes twice returns the first image.")
    @ApiResponse(responseCode = "201", description = "The image reference")
    @ApiResponse(responseCode = "400",
            description = "Not a supported image, truncated, over 5 MB, or of implausible "
                    + "dimensions",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProductImageResponse> uploadImage(
            @PathVariable("id") UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "altText", required = false) String altText) throws IOException {

        log.debug("POST /api/v1/admin/products/{}/images name=\"{}\" declared={} bytes={}",
                id, file.getOriginalFilename(), file.getContentType(), file.getSize());

        ProductImageResponse uploaded = imageService.upload(
                id,
                file.getBytes(),
                file.getOriginalFilename(),
                file.getContentType(),
                altText);
        return ResponseEntity.status(HttpStatus.CREATED).body(uploaded);
    }

    /**
     * Preview an image, including on an unpublished product.
     *
     * <p>Exists because the public endpoint deliberately 404s anything whose
     * product is not ACTIVE, so without this an operator could not see the
     * photographs of the draft they are working on.
     *
     * <p>Served {@code private, no-store}, unlike the public path's one-year
     * immutable cache: this response is specific to an authenticated operator,
     * and letting a shared cache keep it would serve an unpublished product's
     * image to whoever asked next.
     */
    @GetMapping("/{id}/images/{imageId}")
    @Operation(operationId = "previewProductImage",
            summary = "Image bytes for any product, published or not")
    @ApiResponse(responseCode = "200", description = "The image bytes, uncacheable")
    @ApiResponse(responseCode = "404", description = "No such image",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<InputStreamResource> previewImage(
            @PathVariable("id") UUID id,
            @PathVariable("imageId") UUID imageId) {

        ServedImage image = imageService.serveForAdmin(imageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ProductImage", "id", imageId.toString()));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentLength(image.byteSize())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(image.content()));
    }

    /**
     * Remove an image from a product.
     *
     * <p>The product id in the path is part of the identity rather than
     * decoration: without checking it, knowing an image id would be enough to
     * delete that image from any product.
     */
    @DeleteMapping("/{id}/images/{imageId}")
    @Operation(operationId = "deleteProductImage", summary = "Delete one of a product's images")
    @ApiResponse(responseCode = "204", description = "Deleted; no body")
    @ApiResponse(responseCode = "404",
            description = "No such image on that product",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> deleteImage(
            @PathVariable("id") UUID id,
            @PathVariable("imageId") UUID imageId) {
        log.debug("DELETE /api/v1/admin/products/{}/images/{}", id, imageId);
        if (!imageService.delete(id, imageId)) {
            throw new ResourceNotFoundException("ProductImage", "id", imageId.toString());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "delete", summary = "Delete a product")
    @ApiResponse(responseCode = "204", description = "Deleted; no body")
    @ApiResponse(responseCode = "404", description = "No product with that id",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        log.debug("DELETE /api/v1/admin/products/{}", id);
        if (!service.deleteById(id)) {
            throw new ResourceNotFoundException("Product", "id", id.toString());
        }
        return ResponseEntity.noContent().build();
    }
}
