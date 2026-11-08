package com.oriah.checkout.dto;

import java.util.UUID;

import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Place and pay for the bag -- exactly the quote preview showed.
 *
 * <p>No destination country: Oriah delivers within Zambia only, the address's
 * own country decides where delivery is quoted for, and a self-collect order
 * is collected here.
 */
@AddressUnlessSelfCollect
public record ConfirmRequest(
        /**
         * Where to deliver. Not needed when the shopper collects
         * ({@link AddressUnlessSelfCollect}); required for every other option.
         */
        UUID shippingAddressId,

        /**
         * The delivery option the shopper chose from preview's
         * {@code shippingOptions}. This option is charged -- it used to be the
         * cheapest offered, whatever the shopper picked.
         */
        @NotBlank
        @Size(min = 1, max = 64)
        @Pattern(regexp = Quote.DELIVERY_OPTION_PATTERN,
                message = "must be a delivery option code such as SELF_COLLECT")
        String deliveryOption,

        @NotBlank String cardNumber,

        /**
         * Refused with 422 when not blank: codes are retired until Phase 7's
         * scoped promotions. Kept in the contract so a client still sending
         * one is told so, rather than silently ignored.
         */
        String promotionCode,

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
) {}
