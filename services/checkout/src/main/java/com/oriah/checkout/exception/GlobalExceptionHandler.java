package com.oriah.checkout.exception;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Checkout's own problem types, on top of the shared error contract.
 *
 * <h2>What this adds, and what it deliberately does not</h2>
 *
 * <p>Extending {@link OriahExceptionHandlerSupport} inherits every shared
 * mapping, so this class carries only what checkout knows that the platform
 * cannot. Four of the seven confirm failures are not here at all, and each
 * absence is a decision:
 *
 * <ul>
 *   <li>{@link CartEmptyException} is an {@code IllegalStateException}, so the
 *       shared advice already answers <strong>409</strong>. Only the
 *       {@code type} is refined below, so a client can tell "your cart is
 *       empty" from every other conflict.</li>
 *   <li>{@link DownstreamUnavailableException} is a
 *       {@code ResponseStatusException}, so the shared {@code statusCarrying}
 *       handler answers <strong>503</strong> <em>and preserves its
 *       {@code Retry-After}</em>. Handling it here would mean re-adding that
 *       header by hand and eventually forgetting to.</li>
 *   <li>{@link DownstreamRejectedException} is left to the shared catch-all on
 *       purpose: its generic 500 body is exactly right. The message names an
 *       internal service and the status it answered with, which belongs in the
 *       ERROR log — the catch-all does not echo it — and not in a body a
 *       shopper reads and can do nothing with.</li>
 *   <li>Validation, 404s, 405s and the rest are shared concerns and were never
 *       checkout's to redefine.</li>
 * </ul>
 *
 * <p>So the rule this file follows: <strong>inherit the status wherever
 * inheriting it is also correct, and add a handler only for a status or a body
 * the platform could not have known.</strong> That leaves 402, 422, 502 and the
 * one 500 that needs ids in its body.
 *
 * <p>402 in particular is not pushed into the shared starter. It is meaningful
 * in exactly one service, and a shared mapping would be a status the other
 * eleven could never produce correctly.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    private static final URI TYPE_CART_EMPTY =
            URI.create("https://oriah.dev/problems/cart-empty");
    /** Shared with order, which raises the same refusal and checkout passes on by this type. */
    private static final URI TYPE_PRICING_CHANGED = com.oriah.observability.ProblemTypes.PRICING_CHANGED;
    private static final URI TYPE_BAG_NOT_PURCHASABLE = BagNotPurchasableException.TYPE;
    private static final URI TYPE_MIXED_CURRENCY = MixedCurrencyException.TYPE;

    /**
     * 409, with a {@code type} a storefront can branch on.
     *
     * <p>The status is the one the shared {@code IllegalStateException} handler
     * would have produced anyway — this exists so "your cart is empty" is
     * distinguishable from every other conflict without parsing prose.
     */
    @ExceptionHandler(CartEmptyException.class)
    public ResponseEntity<ProblemDetail> cartEmpty(CartEmptyException ex) {
        log.debug("409 cart empty");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, TYPE_CART_EMPTY, HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage()));
    }

    /**
     * 409: the prices moved while the shopper was checking out.
     *
     * <h2>Three conflicts that used to be one</h2>
     *
     * This, {@link #bagNotPurchasable} below and {@link #mixedCurrency} are all
     * conflicts, and the shared {@code IllegalStateException} handler would
     * give all three the same 409 with no {@code type}. The storefront maps
     * every untyped 409 to "Your bag is empty" with retry disabled, so a
     * shopper whose ring was repriced by 50 ngwee would be told their bag was
     * empty while looking at it -- and offered no way forward.
     *
     * <p>{@code changedLines} names the variant ids that moved, so the client
     * can mark those rows rather than the whole bag. It is the caller's own
     * bag, so naming its contents discloses nothing they did not send.
     */
    @ExceptionHandler(PricingChangedException.class)
    public ResponseEntity<ProblemDetail> pricingChanged(PricingChangedException ex) {
        log.info("409 pricing changed for {} line(s)", ex.changedLines().size());
        ProblemDetail body = problem(HttpStatus.CONFLICT, TYPE_PRICING_CHANGED,
                HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage());
        body.setProperty("changedLines", ex.changedLines());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 409: something in the bag cannot be sold at all. */
    @ExceptionHandler(BagNotPurchasableException.class)
    public ResponseEntity<ProblemDetail> bagNotPurchasable(BagNotPurchasableException ex) {
        log.info("409 bag not purchasable: {} line(s)", ex.unavailableLines().size());
        ProblemDetail body = problem(HttpStatus.CONFLICT, TYPE_BAG_NOT_PURCHASABLE,
                HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage());
        body.setProperty("unavailableLines", ex.unavailableLines());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 409: the bag mixes currencies.
     *
     * <p>Separate from {@link #bagNotPurchasable} because the remedy differs --
     * every line is individually fine and the shopper has to split the order,
     * not remove something unavailable.
     */
    @ExceptionHandler(MixedCurrencyException.class)
    public ResponseEntity<ProblemDetail> mixedCurrency(MixedCurrencyException ex) {
        log.warn("409 mixed-currency bag");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, TYPE_MIXED_CURRENCY,
                HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage()));
    }

    /**
     * A refusal by order or shipping the shopper can act on, passed on under its OWN name (Phase 7): no stock (naming every short line), too many holds,
     * a town not yet priced. The status is the downstream's (409); the type is theirs, and extras such as {@code shortages} ride along, so the storefront
     * can say exactly which piece is gone rather than "something went wrong". INFO: an ordinary answer, not a fault.
     */
    @ExceptionHandler(RefusalPassedOnException.class)
    public ResponseEntity<ProblemDetail> refusalPassedOn(RefusalPassedOnException ex) {
        log.info("{} {} passed on to the shopper", ex.status().value(), ex.code());
        ProblemDetail body = problem(ex.status(), URI.create("https://oriah.dev/problems/" + ex.code()), ex.status().getReasonPhrase(), ex.getMessage());
        ex.extras().forEach(body::setProperty);
        return ResponseEntity.status(ex.status()).body(body);
    }
}
