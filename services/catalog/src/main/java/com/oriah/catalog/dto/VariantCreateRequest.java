package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Add a purchasable configuration to a product.
 *
 * <h2>Why unitPrice is required here and nowhere else</h2>
 *
 * This is the ONLY place in the platform where a price may be supplied by a
 * caller, and the caller is an authenticated {@code CATALOG_ADMIN} setting
 * what the business charges. Everywhere else — cart in particular — a price
 * arriving in a request body is the defect this phase exists to remove:
 * cart-service currently accepts {@code unitPrice} from an anonymous shopper
 * and stores it, which was shown to let anything be bought for one cent.
 *
 * @param unitPrice may be 0.00, deliberately. An admin saving a draft before
 *                  deciding on a price is a real workflow, and the variants
 *                  migration created exactly that state for every
 *                  pre-existing product. Zero simply means "not priced yet",
 *                  and the publish rule refuses to put such a product on the
 *                  storefront — so the guard lives at publication rather than
 *                  here, where it would block a legitimate draft.
 * @param currency  optional; defaults to the brand currency. Every variant of
 *                  one product must share a currency, which the product
 *                  enforces, because a card showing one "from" price cannot
 *                  be correct for two currencies at once.
 * @param options   the axis values that distinguish this variant, e.g.
 *                  {@code {"Ring Size": "M"}}. Its key set must equal the
 *                  product's declared option names; empty is valid and means
 *                  the product does not vary.
 */
public record VariantCreateRequest(

        @NotBlank(message = "sku must not be blank")
        @Size(max = 64, message = "sku must be at most 64 characters")
        String sku,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
        BigDecimal unitPrice,

        @Size(max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        Map<String, String> options
) {
}
