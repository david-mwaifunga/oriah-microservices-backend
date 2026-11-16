package com.oriah.notification.belowcost;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Catalog's list of where every priced piece stands against what it cost ({@code GET /api/v1/admin/pricing/below-cost}), asked for as
 * notification itself (its own Keycloak client, {@link CatalogClientIdentity}). The one outbound call notification makes.
 *
 * <p>Catalog is reached directly by its Eureka name, never through the gateway (the gateway refuses this machine identity on every path).
 * Catalog hands the same token on to inventory's cost read, which is why the identity holds COST_VIEWER as well as CATALOG_ADMIN.
 *
 * <p><b>No figure is read.</b> The reply carries the price, the net price, the floor, the shortfall and the lot costs; none is declared
 * below, so none can be stored, logged or emailed by this service. Cost is gated at the API to people who may see it, and an email address in a
 * setting is not that gate.
 */
@FeignClient(name = "catalog", contextId = "catalogBelowCost", configuration = CatalogClientIdentity.class)
public interface CatalogBelowCostClient {

    /**
     * @param status which pieces to list; notification asks for {@code BELOW}
     * @param size   at most 100, catalog's own limit
     */
    @GetMapping("/api/v1/admin/pricing/below-cost")
    BelowCostReply belowCost(@RequestParam("status") String status, @RequestParam("page") int page, @RequestParam("size") int size);

    /**
     * One page of the list. Each call is a fresh evaluation by catalog, so two pages are not one snapshot: {@code totalItems} is compared
     * between pages, and a list that changed meanwhile is thrown away.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BelowCostReply(List<Item> items, Map<String, Long> counts, int page, int size, long totalItems) {
    }

    /**
     * A piece, by what a person would call it. {@code variantTitle} already begins with the product's title.
     *
     * @param productStatus DRAFT, ACTIVE or ARCHIVED: only a piece on sale is told
     * @param assessment    BELOW for a piece on sale for less than it cost
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(UUID variantId, UUID productId, String variantTitle, String sku, String productStatus, String assessment) {
    }
}
