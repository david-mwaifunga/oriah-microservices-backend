package com.oriah.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Catalog, asked to list one returned piece on its own so it can be sold again at a lower price (Phase 12, plan 4.6 and 4.7).
 *
 * <p>Asked with the PERSON's own token ({@link com.oriah.order.config.FeignTokenForwarder}), never as the order system: making a listing needs
 * CATALOG_ADMIN, and it is the person assessing the piece who holds it (order checks that first, so a 403 here would be a surprise). Catalog is
 * idempotent by the piece: the same piece asked again answers the listing it already made, so a repeat after a lost reply is safe. Its own timeouts
 * (0.5 s to connect, 4 s to answer) are in {@code application.yaml}; it is asked once per assessment, on the request thread, never on a shopper's path.
 *
 * <p>No fallback and no circuit breaker: a failed call throws, and the piece simply stays undecided.
 */
@FeignClient(name = "catalog", contextId = "catalogMarkdown", configuration = com.oriah.order.config.FeignTokenForwarder.class)
public interface CatalogMarkdownClient {

    /**
     * 201 the first time, 200 for a repeat; the body (the product) is not read. 404 {@code variant-not-found}; 409 {@code markdown-piece-used},
     * {@code already-marked-down}, {@code packaging-is-not-priced}, {@code sku-unavailable}.
     */
    @PostMapping("/api/v1/admin/products/{productId}/variants/marked-down")
    void create(@PathVariable("productId") UUID productId, @RequestBody MarkdownAsk body);

    /**
     * @param variantId       the id the new listing takes: derived from the piece by order, so every try names the same one
     * @param sourceVariantId the listing the piece was sold under
     * @param pieceId         the piece (order's and inventory's id for it)
     */
    record MarkdownAsk(UUID variantId, UUID sourceVariantId, UUID pieceId) {
    }
}
