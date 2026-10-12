package com.oriah.catalog.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.catalog.service.ProductImageService;
import com.oriah.catalog.service.ServedImage;
import com.oriah.observability.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Serves product image bytes to anyone.
 *
 * <h2>Why catalog proxies the bytes instead of handing out presigned URLs</h2>
 *
 * <ul>
 *   <li>The browser reaches this API same-origin through the storefront's
 *       {@code /api/v1/*} rewrite, and the gateway has no CORS configuration
 *       at all. A presigned URL introduces a second origin and makes the
 *       object store's CORS settings load-bearing for the first time.</li>
 *   <li>A RELATIVE image URL works unchanged in the browser and during
 *       server-side rendering, because the frontend resolves a non-absolute
 *       {@code src} in-process without touching DNS. A presigned URL must name
 *       a host, which differs per environment and would have to be persisted —
 *       making an environment detail part of the data.</li>
 *   <li>Only the server can validate what it stores. A presigned PUT cannot
 *       bound content length and lets the client dictate the content type,
 *       which is precisely what the ingest path refuses to trust.</li>
 * </ul>
 *
 * <h2>This route is deliberately NOT under /api/v1/products/**</h2>
 *
 * That path carries a per-IP rate limiter at replenish 20 / burst 40 plus a
 * circuit breaker. A grid of 24 product cards is 24 or more image requests in
 * a single paint, so serving bytes there would make the storefront throttle
 * itself on first load — and when the breaker opened, an {@code <img>} tag
 * would receive an RFC 7807 problem document where image data was expected.
 * The gateway gives this path its own generous limiter and no breaker.
 */
@RestController
@RequestMapping("/api/v1/product-images")
@Tag(name = "Product images", description = "Image bytes for the storefront")
@Slf4j
public class ProductImageController {

    /**
     * One year, immutable.
     *
     * <p>Safe because the URL is content-addressed in effect: an image row's
     * bytes never change, and re-uploading a different photograph creates a
     * new row with a new id. So a given URL always means the same bytes, and
     * there is nothing for a client to re-validate.
     *
     * <p>The consequence worth stating: bytes already cached by a browser or
     * CDN survive the product being unpublished. The origin stops serving
     * them; existing caches do not. For product photography that is an
     * acceptable trade for not re-fetching every image on every page.
     */
    private static final CacheControl IMMUTABLE_FOR_A_YEAR =
            CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable();

    private final ProductImageService service;

    public ProductImageController(ProductImageService service) {
        this.service = service;
    }

    @GetMapping("/{imageId}")
    @Operation(operationId = "getProductImage",
            summary = "Image bytes for a published product",
            description = "Served with a one-year immutable cache and a strong ETag taken "
                    + "from the stored content hash. Returns 404 -- not 403 -- when the "
                    + "owning product is not ACTIVE, because a 403 would confirm that the "
                    + "image exists. Range requests are ignored and the full entity is "
                    + "returned.")
    @ApiResponse(responseCode = "200", description = "The image bytes")
    @ApiResponse(responseCode = "304", description = "The client's ETag still matches")
    @ApiResponse(responseCode = "404", description = "No such image, or its product is not published",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<InputStreamResource> get(
            @PathVariable("imageId") UUID imageId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        ServedImage image = service.servePublicly(imageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ProductImage", "id", imageId.toString()));

        String etag = "\"" + image.contentHash() + "\"";

        // Checked AFTER resolving the image but before streaming it: a 304
        // must carry the same cache headers as the 200 it replaces, or a
        // revalidating client loses the caching directives and asks again
        // every time.
        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            closeQuietly(image);
            return ResponseEntity.status(304)
                    .eTag(etag)
                    .cacheControl(IMMUTABLE_FOR_A_YEAR)
                    .build();
        }

        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(IMMUTABLE_FOR_A_YEAR)
                .contentLength(image.byteSize())
                .contentType(MediaType.parseMediaType(image.contentType()))
                // The content type was normalised from decoding the image, not
                // taken from the uploader -- but nosniff is still set, because
                // these bytes are served from the storefront's own origin and
                // a browser guessing a type would make any future ingest
                // mistake exploitable rather than merely wrong.
                .header("X-Content-Type-Options", "nosniff")
                // Streamed rather than buffered: this is the highest-volume
                // endpoint in the catalogue and copying each image through the
                // heap is how a service falls over under ordinary traffic.
                .body(new InputStreamResource(image.content()));
    }

    /**
     * Whether the client's {@code If-None-Match} covers our ETag.
     *
     * <p>Handles the comma-separated list and {@code *} forms from RFC 9110,
     * and tolerates the {@code W/} weak prefix. Comparing the raw header to a
     * single ETag — which is the obvious implementation — fails for any client
     * that sends more than one, and the symptom is an image that is re-fetched
     * on every request with no error anywhere.
     */
    private static boolean matches(String ifNoneMatch, String etag) {
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) {
            return true;
        }
        for (String candidate : header.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.startsWith("W/")) {
                trimmed = trimmed.substring(2);
            }
            if (trimmed.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The 304 path resolved the image, which opened a stream nobody will read.
     * Left unclosed it holds a connection from the object store's pool until
     * the finaliser runs, and a page full of cached images would exhaust it.
     */
    private static void closeQuietly(ServedImage image) {
        try {
            image.content().close();
        } catch (Exception e) {
            log.debug("failed to close an unread image stream", e);
        }
    }
}
