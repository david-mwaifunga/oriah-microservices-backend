package com.oriah.checkout.dto;

import java.util.UUID;

import com.oriah.quote.Quote;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PreviewRequest(
        /**
         * Where to quote delivery when no address is given. Optional: Oriah
         * delivers within Zambia only, so an absent country means Zambia.
         * Ignored whenever {@code shippingAddressId} is present -- the
         * address's own country decides.
         */
        @Size(min = 2, max = 2) String destinationCountry,

        /** Refused with 422 when not blank: codes are retired until Phase 7. */
        String promotionCode,

        /**
         * Which of the caller's addresses this quote is for. Optional: a
         * self-collect quote needs none. When present, delivery is quoted for
         * that address's country and {@code destinationCountry} is ignored.
         */
        UUID shippingAddressId,

        /**
         * The delivery option to quote. Optional: without one, preview lists
         * the options and quotes no total and no digest, because there is
         * nothing to agree to until the shopper has chosen.
         */
        @Size(min = 1, max = 64)
        @Pattern(regexp = Quote.DELIVERY_OPTION_PATTERN,
                message = "must be a delivery option code such as SELF_COLLECT")
        String deliveryOption
) {}
