package com.oriah.checkout.dto;

import java.util.UUID;

import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Place the bag -- exactly the quote preview showed. Placing holds the pieces for the shopper; paying is the step after (a mobile-money or bank-transfer
 * reference given to order, which staff then confirm). No card, no promotion code: promotions apply by themselves, per piece, and the quote shows them.
 */
@AddressUnlessSelfCollect
@TownUnlessSelfCollect
public record ConfirmRequest(
        /**
         * Where to deliver. Not needed when the shopper collects
         * ({@link AddressUnlessSelfCollect}); required for every other option.
         */
        UUID shippingAddressId,

        /**
         * The delivery option the shopper chose (see shipping's {@code GET /api/v1/shipping/towns}). This option is charged.
         */
        @NotBlank
        @Size(min = 1, max = 64)
        @Pattern(regexp = Quote.DELIVERY_OPTION_PATTERN,
                message = "must be a delivery option code such as SELF_COLLECT")
        String deliveryOption,

        /** The town the delivery is priced for; required for every option but self-collect ({@link TownUnlessSelfCollect}). */
        UUID deliveryTownId,

        /** Who pays the courier: ORIAH_COLLECTS or RECIPIENT_PAYS (paid on arrival: K0 on this order). Absent takes the one the price offers. */
        @Pattern(regexp = "^(ORIAH_COLLECTS|RECIPIENT_PAYS)$", message = "must be ORIAH_COLLECTS or RECIPIENT_PAYS")
        String payer,

        /**
         * The {@code quoteDigest} the matching preview returned -- quote, then
         * commit. REQUIRED: a nullable digest would let any client opt out of
         * the check by omitting it. It is only ever COMPARED against a value
         * recomputed here, never used as an input to a price, so a forged one
         * buys nothing.
         */
        @NotBlank(message = "quoteDigest is required; take it from the preview response")
        @Size(min = 64, max = 64)
        @Pattern(regexp = QuoteDigest.PATTERN,
                message = "must be 64 lower-case hex characters; take it from the preview response")
        String quoteDigest
) implements DeliveryChoice {}
