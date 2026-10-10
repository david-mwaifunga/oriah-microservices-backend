package com.oriah.checkout.exception;

/**
 * Checkout was confirmed with nothing in the cart.
 *
 * <p>Extends {@link IllegalStateException}, so it inherits <strong>409
 * Conflict</strong> from the shared {@code OriahExceptionHandlerSupport} mapping
 * with no registration anywhere. That is the pattern this codebase settled on:
 * share the status mappings, not the domain vocabulary.
 *
 * <p>409 rather than 400 because the request is well-formed — the caller's
 * <em>state</em> is what is wrong — and deliberately <strong>not</strong> a 5xx,
 * because a 5xx invites a retry and re-sending this can never succeed. (What
 * actually acts on a 5xx here is the client, not the stack: see
 * {@link DownstreamUnavailableException} for who retries what on this path.)
 *
 * <p>This used to be {@code 200 OK} with {@code "message": "cart is empty"},
 * which the storefront rendered as a green "Order placed" toast.
 */
public class CartEmptyException extends IllegalStateException {

    public CartEmptyException() {
        super("The cart is empty, so there is nothing to check out.");
    }
}
