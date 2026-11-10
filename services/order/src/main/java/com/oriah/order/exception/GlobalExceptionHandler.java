package com.oriah.order.exception;

import com.oriah.observability.ProblemTypes;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import feign.FeignException;

import lombok.extern.slf4j.Slf4j;

/**
 * What order knows that the platform-wide advice does not.
 *
 * <h2>Why order needs its own advice now</h2>
 *
 * It had none: {@code OriahExceptionHandlerSupport} rendered everything, which
 * was enough while order made no outbound calls worth distinguishing. Pricing
 * changed that. A {@code FeignException} from catalog would have fallen through
 * to the catch-all and rendered as
 * {@code 500 An unexpected error occurred} -- the same answer for "catalog is
 * down, retry in a moment" and "order sent catalog something malformed", which
 * are opposite instructions to a caller.
 *
 * <p>Adding this class makes {@code ErrorAdviceRegistrationTest} go red,
 * because that test pins which advice owns error rendering in this service.
 * That is the intended consequence: the test exists so a second advice cannot
 * appear unnoticed, and the expectation is updated in the same commit as the
 * advice it is about.
 *
 * <h2>A 4xx from catalog is OUR bug</h2>
 *
 * order caps the batch at 100 ids, which is exactly catalog's
 * {@code @Size(max = 100)}, and sends nothing else. So catalog answering 4xx
 * means order built a malformed request -- a defect here, not a transient
 * condition, and not something a shopper can fix by waiting. Reporting it as
 * 503 would tell them to retry something that will never succeed, so it is a
 * 500, logged at ERROR with the status catalog gave.
 *
 * <p>A 5xx, a connect failure or a read timeout is the opposite: catalog was
 * never asked, or could not answer, and retrying is exactly right. 503.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    private static final URI TYPE_BAG_NOT_PURCHASABLE =
            URI.create("https://oriah.dev/problems/bag-not-purchasable");
    private static final URI TYPE_MIXED_CURRENCY =
            URI.create("https://oriah.dev/problems/mixed-currency");
    /** Shared with checkout, which passes it on to the shopper (ProblemTypes). */
    private static final URI TYPE_PRICE_CLAIM_REJECTED = ProblemTypes.PRICING_CHANGED;
    private static final URI TYPE_TOTAL_TOO_LARGE =
            URI.create("https://oriah.dev/problems/total-too-large");
    private static final URI TYPE_UPSTREAM_UNAVAILABLE =
            URI.create("https://oriah.dev/problems/upstream-unavailable");

    /**
     * Named types for the three pricing refusals, matching checkout's.
     *
     * <p>All three are 409 and all three subclass
     * {@code IllegalStateException}, so the shared advice would already answer
     * 409 -- but with one generic type, and the storefront branches on the
     * type to decide whether a retry can help. A shopper whose price moved
     * needs "review and try again"; one holding a withdrawn item needs to
     * remove it. Same status, different remedy.
     *
     * <p>{@code pricing-changed} is reused verbatim from checkout rather than
     * invented, because it means the same thing to the storefront and is
     * already handled there.
     */
    @ExceptionHandler(BagNotPurchasableException.class)
    public ResponseEntity<ProblemDetail> bagNotPurchasable(BagNotPurchasableException ex) {
        log.warn("409 bag not purchasable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, TYPE_BAG_NOT_PURCHASABLE,
                HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage()));
    }

    @ExceptionHandler(MixedCurrencyException.class)
    public ResponseEntity<ProblemDetail> mixedCurrency(MixedCurrencyException ex) {
        log.warn("409 mixed currency: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, TYPE_MIXED_CURRENCY,
                HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage()));
    }

    @ExceptionHandler(PriceClaimRejectedException.class)
    public ResponseEntity<ProblemDetail> priceClaimRejected(PriceClaimRejectedException ex) {
        log.warn("409 price claim rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, TYPE_PRICE_CLAIM_REJECTED,
                HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage()));
    }

    /** 422: well-formed, not in conflict, simply larger than we can represent. */
    /**
     * 409 pricing-changed, raised before anything is saved: the option the
     * shopper chose is no longer offered, or (item 4.4) the quote moved. Its
     * own detail, distinct from a price-claim refusal of the same type.
     */
    @ExceptionHandler(QuoteChangedException.class)
    public ResponseEntity<ProblemDetail> quoteChanged(QuoteChangedException ex) {
        log.info("409 quote changed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, ProblemTypes.PRICING_CHANGED,
                HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage()));
    }

    /**
     * 503: a service order needs before it can place the order did not answer
     * -- customer (whose address it is), catalog or shipping (the price).
     * Nothing placed; retry is safe. "Place", not "price": the address check
     * runs before pricing, so when customer is down nothing has been priced.
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ProblemDetail> upstreamUnavailable(UpstreamUnavailableException ex) {
        log.warn("503 {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, TYPE_UPSTREAM_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                        "We could not place your order just now. Nothing has been ordered "
                                + "or charged; please try again."));
    }

    /** 500: a dependency refused order's own request, or answered with unusable data. Our fault. */
    @ExceptionHandler(UpstreamRejectedException.class)
    public ResponseEntity<ProblemDetail> upstreamRejected(UpstreamRejectedException ex) {
        log.error("500 {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(
                HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL,
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected error occurred. Use the traceId to correlate with logs and traces."));
    }

    @ExceptionHandler(TotalTooLargeException.class)
    public ResponseEntity<ProblemDetail> totalTooLarge(TotalTooLargeException ex) {
        log.error("422 order total exceeds the money column: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem(
                HttpStatus.UNPROCESSABLE_ENTITY, TYPE_TOTAL_TOO_LARGE,
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(), ex.getMessage()));
    }

    /**
     * Catalog answered badly, or did not answer.
     *
     * <p>The split is the whole point of this handler. {@code status() >= 400
     * && < 500} is catalog rejecting what order sent, which is a bug here;
     * anything else -- 5xx, a connect failure, a read timeout, all of which
     * arrive as {@code FeignException} subclasses -- means the answer was
     * never obtained.
     *
     * <p>That 503 carries {@code Retry-After: 5}, like the one above for
     * customer, Keycloak and shipping. It tells the caller "not straight
     * away", and checkout acts on it: a 503 that carries it is passed on to
     * the shopper at once, while one without it is sent again, twice more,
     * half a second apart. Without the header, every checkout asked a
     * struggling catalog three times -- and customer before it each time --
     * and the shopper waited for all three.
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ProblemDetail> upstream(FeignException ex) {
        int status = ex.status();
        if (status >= 400 && status < 500) {
            // Logged at ERROR with the body, because this is ours to fix and
            // nobody is coming to tell us about it.
            log.error("500 catalog rejected order's pricing request with {}: {}",
                    status, ex.contentUTF8(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(
                    HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL,
                    HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                    "An unexpected error occurred. Use the traceId to correlate with "
                            + "logs and traces."));
        }
        log.error("503 could not price this order: catalog status={}", status, ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, TYPE_UPSTREAM_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                        "We could not price your order just now. Nothing has been ordered "
                                + "or charged; please try again."));
    }
}
