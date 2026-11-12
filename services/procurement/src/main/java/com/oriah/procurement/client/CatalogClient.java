package com.oriah.procurement.client;

import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Asks catalog what variants ARE, so a purchase order never names an id that is no
 * variant (a mistyped id would be ordered, paid for and received, and its cost lots
 * would attach to nothing).
 *
 * <h2>Direct to the service, with the caller's own token</h2>
 *
 * Resolved by name through Eureka and never through the gateway (which has no route for
 * the lookup, so no browser can reach it). The signed-in person's token is forwarded
 * ({@link CatalogTokenForwarder}): catalog answers a CATALOG_ADMIN or a PROCUREMENT_ADMIN,
 * so there is no machine role to grant and no realm change.
 *
 * <h2>No fallback, and that is the point</h2>
 *
 * A fallback would have to invent an answer to "is this a real variant", and every
 * default is wrong: yes lets a typo through, no refuses a good order. A failed call
 * THROWS, the service turns it into a 503, and nothing is saved.
 *
 * <p>The records are this service's own copy of catalog's reply, tolerant of fields it
 * does not know, so catalog can add to its answer without breaking procurement.
 */
@FeignClient(name = "catalog", configuration = CatalogTokenForwarder.class)
public interface CatalogClient {

    @PostMapping("/api/v1/admin/variants/lookup")
    VariantLookupResult lookup(@RequestBody VariantLookupBody body);

    record VariantLookupBody(List<UUID> variantIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantLookupResult(List<Entry> results) {

        /** One per requested id, in order. Everything but {@code variantId} and {@code found} is null when not found. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Entry(UUID variantId, boolean found, UUID productId, String productTitle, String variantTitle,
                            String sku, String kind, String productStatus, Boolean active, Boolean productDeleted) {
        }
    }
}
