package com.oriah.catalog;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.service.ProductService;

/**
 * For the tests whose subject is NOT pricing but which need a priced piece to stand on (a published ring to attach an image to, to
 * delete, to read as a shopper): a variant is created unpriced now, always, and only the pricing endpoint prices it, which compares the
 * price with what stock cost. A fixture does not need that comparison, so it writes the price straight into the variant's row, as the
 * old creation request used to, and leaves the pricing tests to test pricing.
 */
public final class PricedCatalogue {

    private PricedCatalogue() {
    }

    /** Adds a variant (unpriced, as every variant now starts) and gives it {@code price} directly. Returns the product as it was after the add. */
    public static ProductResponse addPricedVariant(ProductService products, JdbcTemplate jdbc, UUID productId, String sku, String price,
                                                   Map<String, String> options, String actor) {
        ProductResponse added = products.addVariant(productId, new VariantCreateRequest(sku, null, "ZMW", options), actor).orElseThrow();
        UUID variantId = added.variants().stream().filter(v -> sku.equals(v.sku())).findFirst().orElseThrow().id();
        priceDirectly(jdbc, variantId, price);
        return added;
    }

    /** Gives the variant that was just added (the LAST of the product in the response to adding it) a price directly. */
    public static void priceLastAdded(JdbcTemplate jdbc, String productJson, String price) {
        try {
            com.fasterxml.jackson.databind.JsonNode variants = new com.fasterxml.jackson.databind.ObjectMapper().readTree(productJson).get("variants");
            priceDirectly(jdbc, UUID.fromString(variants.get(variants.size() - 1).get("id").asText()), price);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("not a product response: " + productJson, e);
        }
    }

    public static void priceDirectly(JdbcTemplate jdbc, UUID variantId, String price) {
        jdbc.update("UPDATE product_variants SET unit_price = ?::numeric WHERE id = ?", price, variantId);
    }
}
