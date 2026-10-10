package com.oriah.checkout.exception;

/**
 * A downstream service answered, and refused the request checkout sent it.
 *
 * <p><strong>500</strong>, because a 4xx from a service-to-service call means
 * <em>checkout</em> built something the callee would not accept — a malformed
 * payload, a missing claim, a stale contract. The shopper did nothing wrong and
 * can do nothing about it, so it is our error, not theirs.
 *
 * <p>The reason this is not folded into {@link DownstreamUnavailableException}:
 * 503 carries {@code Retry-After} and asks the client to come back. A rejected
 * payload will be rejected identically on every attempt, so reporting it as 503
 * invites a retry that cannot succeed and hides a checkout bug as somebody
 * else's outage. "Unreachable" and "reachable and refusing" are genuinely
 * different conditions and only one of them is worth retrying.
 *
 * <p>Checkout's own Resilience4j config already encodes the same judgement
 * one layer down — {@code ignore-exceptions: FeignClientException}, so a 4xx
 * is never retried on an outbound call either. This keeps that consistent at
 * the response boundary.
 *
 * <p>Carries the downstream's name and status for the log line; the response
 * body says only that something went wrong on our side, since which internal
 * service answered what is not the caller's business.
 */
public class DownstreamRejectedException extends RuntimeException {

    private final String service;
    private final int status;

    public DownstreamRejectedException(String service, int status, Throwable cause) {
        super("The %s service rejected the checkout request with %d."
                .formatted(service, status), cause);
        this.service = service;
        this.status = status;
    }

    /**
     * A 2xx with no usable body — answered, but unusably, which is the same
     * class of problem as a refusal and equally not the shopper's to fix.
     */
    public DownstreamRejectedException(String service) {
        super("The %s service answered with no usable body.".formatted(service));
        this.service = service;
        this.status = 0;
    }

    public String service() {
        return service;
    }

    /**
     * The status the downstream answered with, or {@code 0} when the failure
     * was an empty body rather than an error status.
     */
    public int status() {
        return status;
    }
}
