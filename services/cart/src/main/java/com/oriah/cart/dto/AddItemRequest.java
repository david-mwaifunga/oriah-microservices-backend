package com.oriah.cart.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.oriah.cart.model.Cart;

/**
 * What a shopper may say when adding to their bag: which variant, and how many.
 *
 * <h2>Two fields are gone, and that is a breaking contract change</h2>
 *
 * This record used to carry {@code @NotNull @DecimalMin("0.0") BigDecimal
 * unitPrice} and {@code @NotBlank String title}. Both are removed:
 *
 * <ul>
 *   <li>{@code unitPrice} because the client may no longer name a price.
 *       {@code POST /api/v1/carts/me/items} with {@code "unitPrice": 0.01}
 *       bought a K4,500 ring for one cent -- demonstrated live against the
 *       running stack. Cart now resolves the price from catalog before any
 *       mutation. Keeping the property for compatibility would leave the hole
 *       reachable, so it goes.</li>
 *   <li>{@code title} because the line label is now catalog's composed
 *       {@code variantTitle}, not a client string stored verbatim. Side
 *       benefit: the old field carried {@code @NotBlank} with no
 *       {@code @Size} against a {@code VARCHAR(255)} column, so a 256-character
 *       title was a DataIntegrityViolationException rendered as a 500.</li>
 * </ul>
 *
 * <p>Both removals are waived in {@code scripts/openapi-allowed-breaks.txt}
 * with reasons; the gate reports them as two separate "schema property
 * removed" findings because the differ iterates properties one at a time.
 *
 * <h2>A stale client fails SILENTLY, which is why this DTO is not the test</h2>
 *
 * Cart sets no {@code spring.jackson} block, so Boot's
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} applies and a request still sending
 * {@code unitPrice} is accepted with the field ignored. That is the right
 * behaviour -- a storefront mid-deploy keeps working -- but it means a test
 * that posts the stale field and checks the price is green whether or not the
 * catalog lookup happens. {@code AddItemPricingTest} is where the lookup is
 * actually proven.
 *
 * @param quantity capped at {@link Cart#MAX_QUANTITY_PER_LINE}, referenced
 *                 rather than repeated so the DTO and the aggregate cannot
 *                 drift. The DTO's job is to answer an obviously-too-large
 *                 request with a 400 and {@code fieldErrors}; the aggregate
 *                 still has to check the RESULTING quantity, which is the only
 *                 place a legal-looking increment onto an existing line can be
 *                 refused.
 */
public record AddItemRequest(
        @NotNull UUID variantId,
        @NotNull @Min(1) @Max(Cart.MAX_QUANTITY_PER_LINE) Integer quantity
) {}
