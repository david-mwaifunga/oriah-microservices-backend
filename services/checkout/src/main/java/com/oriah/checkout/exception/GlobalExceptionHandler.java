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
    private static final URI TYPE_PROMO_INVALID =
            URI.create("https://oriah.dev/problems/promotion-code-invalid");
    private static final URI TYPE_PAYMENT_DECLINED =
            URI.create("https://oriah.dev/problems/payment-declined");
    private static final URI TYPE_PAYMENT_UNCONFIRMED =
            URI.create("https://oriah.dev/problems/payment-confirmation-failed");
    private static final URI TYPE_CAPTURED_NO_ORDER =
            URI.create("https://oriah.dev/problems/payment-captured-without-order");
    private static final URI TYPE_PRICING_CHANGED =
            URI.create("https://oriah.dev/problems/pricing-changed");
    private static final URI TYPE_BAG_NOT_PURCHASABLE =
            URI.create("https://oriah.dev/problems/bag-not-purchasable");
    private static final URI TYPE_MIXED_CURRENCY =
            URI.create("https://oriah.dev/problems/mixed-currency");

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
     * 422: the request is well-formed, but one value in it cannot be used.
     *
     * <p>The code is logged and not echoed. promotion-service answers a single
     * 404 for unknown, expired, disabled and exhausted codes so the response
     * cannot be used to probe which guard failed; repeating the code or the
     * reason here would hand back the signal that single 404 exists to
     * withhold.
     */
    /**
     * 422, shipping's refusal of an address abroad, passed on rather than
     * flattened into a 500 (Phase 0, item 1). The same problem type shipping
     * uses, from the shared {@code ProblemTypes}, so the storefront keys on one
     * value. Logged at INFO: an ordinary answer, not a fault.
     */
    @ExceptionHandler(OutsideDeliveryAreaException.class)
    public ProblemDetail outsideDeliveryArea(OutsideDeliveryAreaException ex) {
        log.info("422 outside the delivery area");
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, com.oriah.observability.ProblemTypes.OUTSIDE_DELIVERY_AREA,
                "Outside our delivery area", ex.getMessage());
    }

    @ExceptionHandler(PromotionCodeInvalidException.class)
    public ResponseEntity<ProblemDetail> promotionInvalid(PromotionCodeInvalidException ex) {
        log.info("422 promotion code refused code={}", ex.code());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem(
                HttpStatus.UNPROCESSABLE_ENTITY, TYPE_PROMO_INVALID,
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(), ex.getMessage()));
    }

    /**
     * 402, the status this whole tier exists for.
     *
     * <p>A declined card was reported as {@code 200 OK} with
     * {@code "message": "payment declined: ..."}, so the storefront's
     * {@code toast.success("Order placed")} fired and the shopper was left
     * believing they had bought something.
     *
     * <p>The processor's reason is carried in the detail: the shopper needs to
     * know whether to try another card, and it says nothing about our system.
     * INFO rather than WARN — a decline is a normal outcome of taking payments,
     * not an operational event.
     */
    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ProblemDetail> paymentDeclined(PaymentDeclinedException ex) {
        log.info("402 payment declined reason={}", ex.reason());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(problem(
                HttpStatus.PAYMENT_REQUIRED, TYPE_PAYMENT_DECLINED,
                HttpStatus.PAYMENT_REQUIRED.getReasonPhrase(), ex.getMessage()));
    }

    /**
     * 502: the processor gave no usable answer, so whether the card was charged
     * is unknown.
     *
     * <p>Separate from 402 because the two need opposite things from the
     * shopper — "use another card" versus "do not re-enter anything yet" — and
     * from 503 because this one is not safely retryable by a machine. WARN: it
     * is an upstream fault, and a run of these is worth seeing.
     */
    @ExceptionHandler(PaymentConfirmationFailedException.class)
    public ResponseEntity<ProblemDetail> paymentUnconfirmed(PaymentConfirmationFailedException ex) {
        log.warn("502 payment could not be confirmed", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem(
                HttpStatus.BAD_GATEWAY, TYPE_PAYMENT_UNCONFIRMED,
                HttpStatus.BAD_GATEWAY.getReasonPhrase(), ex.getMessage()));
    }

    /**
     * 500, carrying the ids a human needs to put it right.
     *
     * <p>The one failure where money has already moved, and the only handler
     * here that adds extension members to the body. That is the point of it:
     * the shopper has been charged, and the single thing that makes this
     * recoverable for them is a reference support can act on. The intent is
     * their own payment — the happy path returns the same id in
     * {@code paymentIntentId} — so this leaks nothing new.
     *
     * <p>Logged at ERROR with the ids, because a 500 alone tells an operator
     * that something broke and not that a captured payment is sitting
     * unreconciled.
     */
    @ExceptionHandler(PaymentCapturedWithoutOrderException.class)
    public ResponseEntity<ProblemDetail> capturedWithoutOrder(
            PaymentCapturedWithoutOrderException ex) {
        log.error("500 payment captured without order orderId={} intentId={}",
                ex.orderId(), ex.paymentIntentId(), ex);

        ProblemDetail pd = problem(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_CAPTURED_NO_ORDER,
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), ex.getMessage());
        pd.setProperty("paymentIntentId", ex.paymentIntentId());
        pd.setProperty("orderId", ex.orderId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }
}
