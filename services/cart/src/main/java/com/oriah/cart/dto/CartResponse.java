package com.oriah.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.oriah.cart.model.Cart;

/**
 * The cart as the API publishes it.
 *
 * <p>{@code NON_NULL} because {@code updatedAt} genuinely has no value for a
 * user who has never had a cart: {@link com.oriah.cart.service.CartService
 * #getOrEmpty} answers with an unsaved placeholder, so JPA auditing never ran.
 * Jackson's default would serialize that as an explicit {@code "updatedAt":
 * null}, which the published contract does not permit — the OpenAPI schema
 * declares the field a non-nullable, non-required string. Omitting it is what
 * the contract already promises and what "never saved" actually means.
 *
 * <p>Not cosmetic: oriah-web parses this with Zod, whose {@code .optional()}
 * accepts {@code undefined} and rejects {@code null}, so the stray null
 * replaced the bag drawer with a raw validation dump for every shopper who had
 * not yet added anything. Pinned by
 * {@code CartIntegrationTest.emptyCartDoesNotEmitANullUpdatedAt}.
 *
 * <p>Scoped to this record rather than set globally: turning on
 * {@code non_null} platform-wide would silently reshape every response in
 * twelve services, and the only measured violation is here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CartResponse(
        UUID id,
        Instant updatedAt,
        BigDecimal subtotal,
        List<CartItemResponse> items
) {
    public static CartResponse from(Cart c) {
        return new CartResponse(
                c.id(),
                c.updatedAt(),
                c.subtotal(),
                c.items().stream().map(CartItemResponse::from).toList()
        );
    }
}
