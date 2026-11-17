package com.oriah.payment.dto;

import java.util.UUID;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A shopper asks to pay one of their orders through ZynlePay.
 *
 * <p>There is no amount: what is asked for is the order's own total, read from the order. And there is no card detail of any kind: a card is typed
 * on ZynlePay's own page, never on Oriah's. The members below the line exist only so that a request carrying one is refused by name instead of
 * being silently ignored; they are not part of the published request.
 *
 * @param channel {@code MOBILE_MONEY} or {@code CARD}
 * @param phone   the wallet's number, for mobile money (any usual Zambian spelling); never sent for a card
 */
public record StartProviderPaymentRequest(
        @NotNull UUID orderId,
        @NotBlank @Schema(allowableValues = {"MOBILE_MONEY", "CARD"}) String channel,
        @Schema(example = "0961234567") String phone,
        // ----------------------------------------------------------------
        @Schema(hidden = true) Object amount,
        @Schema(hidden = true) Object total,
        @Schema(hidden = true) Object currency,
        @Schema(hidden = true) Object fee,
        @Schema(hidden = true) Object cardNumber,
        @Schema(hidden = true) Object pan,
        @Schema(hidden = true) Object cvv,
        @Schema(hidden = true) Object cvc,
        @Schema(hidden = true) Object expiry,
        @Schema(hidden = true) Object card) {

    /** Whether the request names an amount: it never may. */
    public boolean typesAnAmount() {
        return amount != null || total != null || currency != null || fee != null;
    }

    /** Whether the request carries anything of a card: it never may. */
    public boolean carriesCardDetails() {
        return cardNumber != null || pan != null || cvv != null || cvc != null || expiry != null || card != null;
    }
}
