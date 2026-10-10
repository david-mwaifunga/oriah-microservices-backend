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
 *   <li><strong>Checkout already retried.</strong> Its own Resilience4j
 *       instances retry each outbound call 3 times, 500ms apart, on
 *       {@code IOException} and {@code FeignServerException} and never on a
 *       4xx. So a 503 reaching the client means those attempts are already
 *       spent — which is also why {@code Retry-After} is seconds and not
 *       milliseconds.</li>
 *   <li><strong>The gateway does not retry this.</strong> Its retry filter is
 *       {@code setMethods(HttpMethod.GET)}, so {@code POST /confirm} is never
 *       replayed at the edge. Deliberate: auto-replaying a POST that moves
 *       money is how a shopper gets charged twice.</li>
 *   <li><strong>The client retries.</strong> That is what this status is for.
 *       The storefront offers "Try again" on a 503 and on nothing else, since
 *       this is the only failure raised before any money moves.</li>
 * </ul>
 *
 * <p>Reported as the old {@code 200 OK} with
 * {@code "message": "order service unavailable"}, none of that could happen:
 * a 200 is not an error, so neither a filter nor the client could recognise a
 * transient outage as something to retry at all.
 *
 * <p>Raised only <strong>before</strong> any money moves. Once a payment is
 * captured the failure is no longer transient and is no longer this exception —
 * see {@link PaymentCapturedWithoutOrderException}.
 */
public class DownstreamUnavailableException extends ResponseStatusException {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final String service;

    public DownstreamUnavailableException(String service, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "The %s service is temporarily unavailable. Nothing has been charged; "
                        .formatted(service) + "retry shortly.",
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
