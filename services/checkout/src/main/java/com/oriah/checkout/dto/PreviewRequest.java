package com.oriah.checkout.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PreviewRequest(
        @NotBlank @Size(min = 2, max = 2) String destinationCountry,
        String promotionCode,

        /**
         * Which of the caller's addresses this quote is for.
         *
         * <p>When present, the shipping zone is derived from that address's
         * country and {@code destinationCountry} is ignored -- the country
         * decides the shipping charge, so it is not the client's to name.
         *
         * <p>Nullable, which keeps the change additive: a caller that omits it
         * still gets a quote based on {@code destinationCountry}. That is safe
         * because preview only QUOTES. Confirm always has an address and always
         * derives from it, so the charge cannot be influenced this way.
         */
        UUID shippingAddressId  // optional
) {}
