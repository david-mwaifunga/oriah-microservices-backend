package com.oriah.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.oriah.cart.model.Cart;
import com.oriah.cart.client.CartPricing;

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
 *
 * @param checkoutEnabled a PRIMITIVE boolean, and that is load-bearing rather
 *                        than a style choice. {@code NON_NULL} above means a
 *                        {@code Boolean} would be OMITTED from the payload
 *                        whenever it happened to be null, and oriah-web's Zod
 *                        {@code .optional()} accepts {@code undefined} — so a
 *                        degraded, checkout-blocked bag would read to the
 *                        browser as checkout-ENABLED, which is the one failure
 *                        this flag exists to prevent. A primitive cannot be
 *                        null. Pinned on the raw body by
 *                        {@code CartReadModelTest.theBlockIsAlwaysPresentInTheBody}.
 * @param currency        the one currency every priced line shares, or null
 *                        when the bag is empty or its lines disagree. Null and
 *                        {@code checkoutEnabled: false} travel together in the
 *                        disagreement case: reporting one of two currencies
 *                        would be a lie, and adding the two numbers together
 *                        downstream would be worse.
 * @param subtotal        the sum of the line totals this response SHOWS, so the
 *                        bag always adds up to itself. Lines that cannot be
 *                        sold show a zero line total and therefore contribute
 *                        nothing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CartResponse(
        UUID id,
        Instant updatedAt,
        BigDecimal subtotal,
        String currency,
        boolean checkoutEnabled,
        List<CartItemResponse> items
) {

    /**
     * Builds the read model from a cart and catalog's answer about it.
     *
     * <p>Every endpoint that returns a cart goes through here — GET, POST
     * /items and PATCH /items/{variantId}. Giving the add path a lookup and
     * leaving the quantity-edit path without one would mean a shopper nudging a
     * quantity received a bag whose every line was UNKNOWN with checkout
     * blocked, which is a worse bug than the one being fixed.
     */
    public static CartResponse from(Cart c, CartPricing pricing) {
        List<CartItemResponse> items = c.items().stream()
                .map(i -> pricing.available()
                        ? CartItemResponse.from(i, pricing.entryFor(i.variantId()))
                        : CartItemResponse.degraded(i))
                .toList();

        BigDecimal subtotal = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Only lines showing money have a currency worth agreeing on; a
        // withdrawn line reports none.
        Set<String> currencies = items.stream()
                .map(CartItemResponse::currency)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        String currency = currencies.size() == 1 ? currencies.iterator().next() : null;

        boolean checkoutEnabled = pricing.available()
                && !items.isEmpty()
                && items.stream().allMatch(CartItemResponse::isPurchasable)
                && currencies.size() == 1;

        return new CartResponse(c.id(), c.updatedAt(), subtotal, currency, checkoutEnabled, items);
    }
}
