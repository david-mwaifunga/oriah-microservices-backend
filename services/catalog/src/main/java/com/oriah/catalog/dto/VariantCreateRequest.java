package com.oriah.catalog.dto;

import com.oriah.money.NgweeAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Add a purchasable configuration to a product. It starts UNPRICED, always.
 *
 * <h2>Why creating a variant does not price it</h2>
 *
 * The only way a variant gets a price is {@code PUT /api/v1/admin/pricing/variants/{variantId}/price}, which compares the price with
 * what the stock cost, asks for a typed reason below it, and writes the dated history. The real-world order is: create the product and
 * its variants, receive the consignment that brings them in, price, publish. A second way in would let a price skip that check and
 * leave a history without its first row.
 *
 * <p>The old {@code unitPrice} field is still read, so a client that sends {@code 0.00} (which has always meant "not priced") keeps
 * working, but anything above zero is refused with a 400 naming the field: a client that still sends a price must hear that it was
 * not taken, not have it silently dropped. It is hidden from the API description for the same reason it is refused.
 *
 * @param currency optional; ZMW (any case), the shop's currency, which is also the default. Prices are in Kwacha only: the lots are
 *                 costed in it, and a price in another could not be compared with them.
 * @param options  the axis values that distinguish this variant, e.g.
 *                 {@code {"Ring Size": "M"}}. Its key set must equal the
 *                 product's declared option names; empty is valid and means
 *                 the product does not vary.
 */
public record VariantCreateRequest(

        @NotBlank(message = "sku must not be blank")
        @Size(max = 64, message = "sku must be at most 64 characters")
        String sku,

        @Schema(hidden = true)
        @DecimalMin(value = "0.00", message = "a variant is created unpriced: set its price with PUT /api/v1/admin/pricing/variants/{variantId}/price")
        @DecimalMax(value = "0.00", message = "a variant is created unpriced: set its price with PUT /api/v1/admin/pricing/variants/{variantId}/price")
        @NgweeAmount
        BigDecimal unitPrice,

        @Size(max = 3, message = "currency must be a 3-letter ISO 4217 code")
        @Pattern(regexp = "(?i)ZMW", message = "prices are in ZMW: no other currency can be compared with what the stock cost")
        String currency,

        Map<String, String> options
) {
}
