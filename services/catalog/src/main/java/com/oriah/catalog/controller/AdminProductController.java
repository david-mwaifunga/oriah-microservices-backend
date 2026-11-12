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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import com.oriah.catalog.constants.CatalogConstants;
import com.oriah.catalog.dto.PageResponse;
import com.oriah.catalog.dto.ProductCreateRequest;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.dto.VariantUpdateRequest;
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
     * telling a caller where the new resource lives. It points at the ADMIN
     * read, because a new product is a DRAFT (and packaging is never anything
     * else) and the public read answers 404 for anything not published; it used
     * to point there, at a URL that could not return what had just been made.
     */
    @PostMapping
    @Operation(operationId = "create", summary = "Create a product",
            description = "`kind` is SELLABLE (the default) or PACKAGING and is fixed for the life of "
                    + "the product. Packaging is stocked and costed like anything else but is never "
                    + "published and never priced: it is typed PACKAGING, and its variants are "
                    + "created at 0.00.")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the admin read path",
            headers = @io.swagger.v3.oas.annotations.headers.Header(
                    name = "Location", description = "/api/v1/admin/products/{id}",
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Validation failure; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        log.debug("POST /api/v1/admin/products title=\"{}\"", request.title());
        ProductResponse saved = service.create(request.title(), request.description(),
                request.tags(), request.productType(), request.attributes(),
                request.categoryIds(), request.primaryCategoryId(), request.kind(), request.optionNames(),
                actor(jwt));
        // The admin read, not the public one: what was just made is a DRAFT.
        URI location = URI.create(CatalogConstants.ADMIN_BASE + "/" + saved.id());
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
            @Valid @RequestBody ProductUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        log.debug("PATCH /api/v1/admin/products/{}", id);
        return service.update(id, request, actor(jwt))
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
     * The operator's catalogue list: drafts, packaging and everything else the
     * public list hides.
     *
     * <p>Also the picker a consignment line chooses its variant from (Phase 4): a
     * piece is often created as a draft just before the shipment that brings it
     * in is keyed, and packaging is never anything but a draft.
     */
    @GetMapping
    @Operation(operationId = "listForAdmin",
            summary = "List products for an operator, drafts and packaging included",
            description = "Every product that has not been deleted, newest first, whatever its "
                    + "status or kind. Each row carries its variants. `kind` and `status` filter; "
                    + "`q` is plain text matched case-insensitively against the title and every "
                    + "variant's SKU (a percent sign or underscore in it means itself). A page is "
                    + "at most 100 rows; one past the end is empty.")
    @ApiResponse(responseCode = "200", description = "A page of products, possibly empty")
    @ApiResponse(responseCode = "400", description = "A kind or status that does not exist, or an "
            + "unparseable page or size",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<ProductResponse> list(
            @RequestParam(value = "kind", required = false) ProductKind kind,
            @RequestParam(value = "status", required = false) ProductStatus status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + CatalogConstants.DEFAULT_PAGE_SIZE) int size) {
        log.debug("GET /api/v1/admin/products kind={} status={} page={} size={}", kind, status, page, size);
        return PageResponse.from(service.findForAdmin(kind, status, q, page, size), r -> r);
    }

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
            @Valid @RequestBody VariantCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        log.debug("POST /api/v1/admin/products/{}/variants sku=\"{}\"", id, request.sku());
        ProductResponse updated = service.addVariant(id, request, actor(jwt))
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id.toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(updated);
    }

    /**
     * Reprice an existing variant.
     *
     * <p>PATCH rather than PUT because it changes one attribute of a variant
     * and leaves the rest -- SKU, options, active flag -- alone. Omitting
     * {@code currency} keeps the variant's own, which matters: catalog reads
     * an absent currency as ZMW everywhere else, and a dollar-priced piece
     * must not be redenominated by a request that never mentioned currency.
     *
     * <p>404 for an unknown VARIANT as well as an unknown product. The
     * aggregate reports a missing variant as IllegalArgumentException, which
     * the shared advice renders 400 -- telling an operator their request was
     * malformed when what is actually wrong is the id they addressed.
     *
     * <p>409 when the variant is the last purchasable one on a published
     * product and the new price is zero: the request is well formed and the
     * product's state is what refuses it. A storefront list filters on
     * {@code unit_price > 0}, so allowing it would drop a live piece out of
     * the grid while it still answered on its own URL.
     */
    @PatchMapping("/{id}/variants/{variantId}")
    @Operation(operationId = "repriceVariant", summary = "Change an existing variant's price")
    @ApiResponse(responseCode = "200", description = "The product, with the new price")
    @ApiResponse(responseCode = "400",
            description = "Validation failure: a missing, negative or implausibly large "
                    + "price, or a currency differing from the product's other variants",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such product, or no such variant on it",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "The variant is the last purchasable one on a published product "
                    + "and the new price is zero",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ProductResponse repriceVariant(
            @PathVariable("id") UUID id,
            @PathVariable("variantId") UUID variantId,
            @Valid @RequestBody VariantUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        log.debug("PATCH /api/v1/admin/products/{}/variants/{} price={}",
                id, variantId, request.unitPrice());
        return service.repriceVariant(id, variantId, request, actor(jwt))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Variant", "id", variantId.toString()));
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
    public ProductResponse publish(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        log.debug("POST /api/v1/admin/products/{}/publish", id);
        return service.publish(id, actor(jwt))
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
    @ApiResponse(responseCode = "503",
            description = "Catalog has no room to work on another photograph right now (it "
                    + "limits how many it decodes at once). Nothing was stored. Try again "
                    + "after the number of seconds in the Retry-After header.",
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
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        log.debug("DELETE /api/v1/admin/products/{}", id);
        if (!service.deleteById(id, actor(jwt))) {
            throw new ResourceNotFoundException("Product", "id", id.toString());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Who made the change, as catalog's event log records it:
     * {@code user:<keycloak subject>}. Taken from the verified token, never
     * from the request, so nobody can write a change in someone else's name.
     */
    private static String actor(Jwt jwt) {
        return "user:" + jwt.getSubject();
    }
}
