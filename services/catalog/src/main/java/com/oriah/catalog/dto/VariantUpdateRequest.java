package com.oriah.catalog.dto;

import com.oriah.money.NgweeAmount;
import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A change to an existing variant's price.
 *
 * <h2>Why unitPrice is required on a PATCH</h2>
 *
 * This is the only field the request carries, so omitting it asks for nothing
 * and almost certainly means a caller sent the wrong body. Accepting it would
 * answer 200 for a request that changed nothing, which reads as success.
 * Repricing to zero is a different thing and is said explicitly; the aggregate
 * refuses it when the variant is the last purchasable one.
 *
 * <h2>Why currency is optional, and what omitting it means</h2>
 *
 * Omitting it keeps the variant's CURRENT currency. That is the PATCH
 * semantic, and here it is load-bearing rather than merely tidy:
 * {@code ProductVariant.normaliseCurrency} turns a null into ZMW, so passing
 * the absent value straight through would silently redenominate a dollar piece
 * to Kwacha at the same number. Eleven of the live catalogue's products are
 * priced in USD.
 *
 * <h2>The ceiling</h2>
 *
 * {@code unit_price} is NUMERIC(12,2) whose only rule is {@code >= 0}, so a
 * mistyped price is accepted by catalog and surfaces much later as a 422 from
 * order-service's own ceiling, at a shopper's checkout, where neither they nor
 * the admin can connect it to the typo. The bound here is the same figure
 * order-service uses for a whole order -- 9,999,999,999.99, the largest value
 * the shared money column can hold -- so the refusal happens at the screen
 * where the number was typed.
 */
@Schema(description = "A change to an existing variant's price")
public record VariantUpdateRequest(

        @Schema(description = "The new price, in the variant's currency", example = "4600.00")
        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
        @DecimalMax(value = "9999999999.99", message = "unitPrice is implausibly large")
        @NgweeAmount
        BigDecimal unitPrice,

        @Schema(description = "ISO 4217 code. Omit to keep the variant's current currency.",
                example = "ZMW")
        @Size(max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency
) {
}
