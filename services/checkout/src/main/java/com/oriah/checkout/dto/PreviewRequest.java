package com.oriah.checkout.dto;

import java.util.UUID;

import com.oriah.quote.Quote;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Price the caller's bag, and -- for the delivery the shopper chose -- quote the whole thing. What is offered where (towns, options, who may pay the
 * courier) is shipping's {@code GET /api/v1/shipping/towns}, read by the storefront directly: preview prices a CHOICE, it does not list the choices.
 */
@TownUnlessSelfCollect
public record PreviewRequest(
        /**
         * Which of the caller's addresses this quote is for. Optional: a self-collect quote needs none. When present it must be one of the caller's own.
         */
        UUID shippingAddressId,

        /**
         * The delivery option to quote. Optional: without one, preview prices the bag and quotes no total and no digest, because there is nothing to
         * agree to until the shopper has chosen how the piece reaches them.
         */
        @Size(min = 1, max = 64)
        @Pattern(regexp = Quote.DELIVERY_OPTION_PATTERN,
                message = "must be a delivery option code such as SELF_COLLECT")
        String deliveryOption,

        /** The town the delivery is priced for; required with any option but self-collect. */
        UUID deliveryTownId,

        /** Who pays the courier; absent takes the one the price offers. */
        @Pattern(regexp = "^(ORIAH_COLLECTS|RECIPIENT_PAYS)$", message = "must be ORIAH_COLLECTS or RECIPIENT_PAYS")
        String payer
) implements DeliveryChoice {}
