package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One purchasable configuration, as the storefront sees it.
 *
 * <p>{@code id} is what the client sends to the cart as its {@code variantId}.
 * For products that predate the variants migration it equals the product id,
 * which is what keeps existing cart, order, inventory and search rows valid;
 * for anything created since, it does not. Clients must therefore treat it as
 * opaque and never assume {@code product.id == variantId}.
 *
 * <p>{@code sku} is a real stock-keeping unit. The PDP currently renders
 * {@code SKU {product.id.slice(0,8)}} -- a truncated UUID presented as a SKU,
 * which is not a number anyone can look up.
 *
 * @param options      axis to value, e.g. {@code {"Ring Size": "M"}}; empty
 *                     for a product that does not vary
 * @param availability stock state, looked up from inventory. Null on list
 *                     responses, where one call per variant would be N calls
 *                     per page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariantResponse(
        UUID id,
        String sku,
        BigDecimal unitPrice,
        String currency,
        Map<String, String> options,
        boolean active,
        List<UUID> imageIds,
        String availability,
        /** {@code NEW}, or {@code MARKED_DOWN} for the listing of one returned piece sold again at a lower price. */
        String condition,
        /** For a marked-down variant, the variant its piece was sold as; absent otherwise. */
        UUID sourceVariantId
) {
}
