package com.oriah.shipping.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.oriah.money.NgweeAmount;

public record RateRequest(
        /** Two-letter ISO country code. Anything but ZM is refused with a 422. */
        @NotBlank @Size(min = 2, max = 2) String destinationCountry,
        @NotNull @DecimalMin("0.00") @NgweeAmount BigDecimal subtotal,

        /**
         * What {@code subtotal} is denominated in, and what the caller needs
         * the quote in. NULLABLE on purpose: adding it had to be an additive
         * change to the published contract, so a caller that does not send it
         * still gets a quote in this service's own currency. Checkout always
         * sends it, because a shipping cost in one currency added to a bag
         * total in another is how a 45-dollar courier charge lands on a Kwacha
         * order as though it were 45 Kwacha.
         */
        @Size(min = 3, max = 3) String currency,

        /**
         * Optional: quote only this option -- the one the shopper chose. An
         * option that is not offered gives an empty list, which the caller
         * reads as "no longer available", rather than an error.
         */
        @Size(max = 64)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$", message = "must be an option code such as SELF_COLLECT")
        String deliveryOption
) {}