package com.oriah.order.client;

import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Asks catalog what a variant is CALLED (Phase 8), so a packing slip can say which box goes out. Identity only: no price, no cost.
 *
 * <h2>The packer's own token, never order's machine token</h2>
 *
 * {@code POST /api/v1/admin/variants/lookup} is catalog's internal door (the gateway has no route for it) and answers CATALOG_ADMIN, PROCUREMENT_ADMIN and
 * FULFILMENT_AGENT. Order forwards the signed-in packer's own token ({@link com.oriah.order.config.FeignTokenForwarder}), so catalog sees who is asking and
 * no machine role is widened. It depends on the call running on the request thread, which it does (the slip is read on it).
 *
 * <h2>A name is a courtesy, so there is no fallback and no failure either</h2>
 *
 * The slip answers 200 when catalog cannot name a box: the box's id is on the slip either way. Whatever goes wrong is logged (a refusal at ERROR, because it
 * means a role or a path is wrong; an outage at WARN) and the slip says names are unavailable.
 */
@FeignClient(name = "catalog", contextId = "catalogLookup", configuration = com.oriah.order.config.FeignTokenForwarder.class)
public interface CatalogLookupClient {

    @PostMapping("/api/v1/admin/variants/lookup")
    Result lookup(@RequestBody Lookup body);

    /**
     * The boxes a sale can go out in (Phase 9), by name and SKU: what a salesperson picks from when the product's default box has run out. Catalog answers
     * SALES_AGENT, FULFILMENT_AGENT and CATALOG_ADMIN, with the caller's own token; identity only, never a price.
     */
    @org.springframework.web.bind.annotation.GetMapping("/api/v1/admin/variants/packaging")
    List<Box> packaging();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Box(UUID variantId, UUID productId, String title, String variantTitle, String sku) {
    }

    record Lookup(List<UUID> variantIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(List<Entry> results) {

        /** One per requested id, in order; the titles are null when not found. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Entry(UUID variantId, boolean found, String productTitle, String variantTitle) {
        }
    }
}
