package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Set a variant's price. The only way a variant gets one: creating a variant does not price it.
 *
 * @param unitPrice         above zero, to the ngwee
 * @param currency          the shop's currency (ZMW) or absent, which means the same; any other is refused
 * @param effectiveFrom     when it takes effect: absent means now. Never in the future, never more than 72 hours back (configurable),
 *                          never before the price it follows. A paper tag changed this morning and keyed tonight is real.
 * @param belowCostReason   why a price below the cost is accepted, 5 to 200 characters, in the person's words. Needed only when the
 *                          price is below the dearest cost still held (plus the default box's); ignored otherwise
 * @param acknowledgedFloor the floor the person was shown when they accepted a price below cost. If the floor has risen since, the
 *                          warning comes back with the new figures, so a reason is never accepted against figures nobody saw
 * @param expectedPriceId   the id of the price the person saw in force, or null if they saw it unpriced. A price saved in between
 *                          makes this stale (409): two people pricing one piece never overwrite each other unseen
 */
public record SetPriceRequest(

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.01", message = "unitPrice must be above zero")
        @NgweeAmount
        BigDecimal unitPrice,

        @Size(max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        Instant effectiveFrom,

        @Size(min = 5, max = 200, message = "belowCostReason must be 5 to 200 characters")
        String belowCostReason,

        @NgweeAmount
        BigDecimal acknowledgedFloor,

        UUID expectedPriceId
) {
}
