package com.oriah.checkout.exception;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A service the checkout saga depends on could not be reached.
 *
 * <p>Extends {@link ResponseStatusException}, which is the second half of the
 * share-the-mappings pattern: the shared advice's {@code statusCarrying} handler
 * already preserves both the status <em>and the headers</em> of anything that
 * carries them, so this gets <strong>503</strong> plus a {@code Retry-After}
 * without the starter learning anything about checkout.
 *
 * <p>The {@code Retry-After} is the point, and it is worth being exact about
 * who acts on it, because "retryable" is easy to overclaim:
 *
 * <ul>
 *   <li><strong>Checkout has already done what it can.</strong> Its own
 *       Resilience4j instances retry each outbound call up to 3 times, 500ms
 *       apart, when a second attempt cannot do anything twice: a connection
 *       never opened, or a 502, 503 or 504 that did not ask for later -- never
 *       a 4xx, never a 500, and never a reply that was lost. A 502, 503 or 504 that carries
 *       {@code Retry-After} is not repeated at all: the service has said it
 *       needs time, and checkout answers with this 503 and its own
 *       {@code Retry-After} (always the 5 seconds below; the downstream's
 *       value is not copied). Or it made no attempt at all,
 *       because the circuit breaker was open or the rate limiter refused:
 *       those protect a service that is already struggling, and are the
 *       reason to wait. Either way a 503 reaching the client means another
 *       immediate try is pointless — which is why {@code Retry-After} is
 *       seconds and not milliseconds.</li>
 *   <li><strong>The gateway does not retry this.</strong> Its retry filter is
 *       {@code setMethods(HttpMethod.GET)}, so {@code POST /confirm} is never
 *       replayed at the edge. Deliberate: auto-replaying a POST that places an
 *       order and holds stock is how a shopper ends up holding it twice.</li>
 *   <li><strong>The client retries.</strong> That is what this status is for.
 *       The storefront offers "Try again" on a 503 and on nothing else, and
 *       sends the SAME {@code Idempotency-Key} with it: a 503 is the one
 *       failure a retry can cure, and the key is what keeps the retry from
 *       placing a second order if the first did go through.</li>
 * </ul>
 *
 * <p>Reported as the old {@code 200 OK} with
 * {@code "message": "order service unavailable"}, none of that could happen:
 * a 200 is not an error, so neither a filter nor the client could recognise a
 * transient outage as something to retry at all.
 *
 * <p>Checkout touches no money, so no outage can strand any. What an outage can
 * hide is an ORDER: if {@code order.place} did its work and the reply was lost,
 * the shopper has an order they were not told about. The retry is safe only
 * because it carries the same {@code Idempotency-Key}, which order turns into
 * the same order rather than a second one, so the 503 says so and never claims
 * that nothing was ordered.
 */
public class DownstreamUnavailableException extends ResponseStatusException {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final String service;

    public DownstreamUnavailableException(String service, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "The %s service is temporarily unavailable; try again shortly. If you were placing an order, retry with the same Idempotency-Key: "
                        .formatted(service) + "an order placed before the reply was lost comes back as that same order, never a second one.",
                cause);
        this.service = service;
    }

    /** Which dependency failed. Logged, never put in the response body. */
    public String service() {
        return service;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        // Roughly the breaker's wait-in-open window, so a client that honours
        // the header retries at about the moment the circuit half-opens rather
        // than hammering a service that is still down.
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER.toSeconds()));
        return headers;
    }
}
