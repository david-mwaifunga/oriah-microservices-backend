package com.oriah.checkout.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Order, as checkout asks it (Phase 7): place the bag. Order prices everything again, takes a HOLD on the pieces at inventory, and answers with the
 * order; payment is a separate step the shopper takes afterwards (a mobile-money or bank-transfer reference, then staff confirm the money).
 *
 * <p>No {@code fallback}, for the reason {@link ShippingClient} gives.
 */
@FeignClient(name = "order")
public interface OrderClient {

    /**
     * @param idempotencyKey the browser's {@code Idempotency-Key}, forwarded verbatim so a retry of the same submission places the same order; null
     *                       (an absent header) is deliberately not replaced by a generated key: a key minted per attempt guards nothing
     */
    @PostMapping("/api/v1/orders")
    OrderResponse place(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody PlaceOrderRequest body);

    /** {@code unitPrice} is the LIST price; order takes the promotion's discount from catalog itself and refuses a quote that does not match. */
    record PlaceOrderRequest(UUID shippingAddressId, String currency, List<LineItem> items, String deliveryOption, UUID deliveryTownId, String payer,
                             String quoteDigest) {
    }

    record LineItem(UUID variantId, String title, Integer quantity, BigDecimal unitPrice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponse(UUID id, String keycloakId, String status, BigDecimal subtotal, BigDecimal shippingAmount, BigDecimal discountAmount,
                         BigDecimal total, String currency, UUID deliveryTownId, String deliveryPayer, Instant holdExpiresAt) {
    }
}
