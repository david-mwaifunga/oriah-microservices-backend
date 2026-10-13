package com.oriah.checkout.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConfirmRequest(
        @NotNull UUID shippingAddressId,
        @NotBlank @Size(min = 2, max = 2) String destinationCountry,
        @NotBlank String cardNumber,
        String promotionCode,

        /**
         * The {@code pricingVersion} the matching preview returned.
         *
         * <p>REQUIRED, and that is the whole guarantee: a nullable version
         * would let any client opt out of the staleness check by omitting it,
         * which is the one thing the check exists to prevent. The cost is a
         * breaking contract change, waived with a written reason in
         * scripts/openapi-allowed-breaks.txt.
         *
         * <p>It is only ever COMPARED against a value recomputed here, never
         * used as an input to a price, so a forged token buys nothing: the
         * worst a client can do with a wrong value is refuse its own checkout.
         */
        @NotBlank(message = "pricingVersion is required; take it from the preview response")
        String pricingVersion  // optional
) {}
