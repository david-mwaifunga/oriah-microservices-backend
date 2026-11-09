package com.oriah.checkout.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

import feign.FeignException;

/**
 * Wraps a downstream Feign call with the full Resilience4j toolbox
 * (RateLimiter -> CircuitBreaker -> Retry) and runs it
 * <b>synchronously on the calling thread</b>.
 *
 * <p>Why synchronous matters: Spring Cloud's OpenFeign + Resilience4j
 * integration ({@code spring.cloud.openfeign.circuitbreaker.enabled =
 * true}) dispatches every Feign call to a ForkJoinPool worker. Our
 * {@link com.oriah.checkout.config.FeignTokenForwarder} reads the
 * Authorization header off the {@link
 * org.springframework.security.core.context.SecurityContextHolder},
 * which is a {@link ThreadLocal}. On the pool worker that ThreadLocal
 * is empty, the Authorization header is dropped, and every downstream
 * service returns 401. By assembling the decorators ourselves and
 * invoking {@code decorate().get()} on the Tomcat servlet thread, the
 * SecurityContext stays populated and the JWT propagates correctly.
 *
 * <p>Decorator order (innermost to outermost):
 * <ol>
 *   <li>{@link RateLimiter} -- counts every attempt, including retries.</li>
 *   <li>{@link CircuitBreaker} -- records each attempt's outcome.</li>
 *   <li>{@link Retry} -- on failure, re-invokes the chain.</li>
 * </ol>
 *
 * <p>{@code name} is both the bean lookup key (every name has a matching
 * {@code resilience4j.{circuitbreaker,retry,ratelimiter}.instances.<name>}
 * block in {@code application.yaml}: one per Feign client, plus
 * {@code order-pay} and {@code payment-create} for two calls of their own)
 * and the log/span tag for the call.
 *
 * <p>Two call styles, because the two readers of a failure want
 * opposite things:
 *
 * <ul>
 *   <li>{@link #call} swallows the failure and returns {@code fallback}.
 *       Right for a <strong>read</strong> like checkout preview, where a
 *       missing shipping quote should still render a page.</li>
 *   <li>{@link #callOrThrow} propagates it. Right for <strong>confirm</strong>,
 *       where every cause used to collapse into one {@code null} and
 *       therefore into one {@code 200 OK} — a down service, a declined
 *       card and an open circuit were indistinguishable to the saga, so
 *       they were indistinguishable to the shopper.</li>
 * </ul>
 *
 * <p>{@link #answeredStatus} is the companion of {@code callOrThrow}: it
 * separates "the downstream answered, and said no" from "the downstream
 * never answered", which is the difference between a 500 and a
 * retry-eligible 503.
 */
@Component
@Slf4j
public class ResilientFeignCaller {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;

    public ResilientFeignCaller(CircuitBreakerRegistry circuitBreakerRegistry,
                                RetryRegistry retryRegistry,
                                RateLimiterRegistry rateLimiterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    /**
     * Run the call and return {@code fallback} if it fails for any reason.
     *
     * <p>Graceful degradation, for reads only. Do not use it on a path where
     * the caller must act differently depending on <em>why</em> the call
     * failed: the fallback erases that, and whatever the caller does next will
     * be the same whether the service is down or the answer was a refusal.
     */
    public <T> T call(String name, Supplier<T> supplier, T fallback) {
        try {
            return callOrThrow(name, supplier);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Run the call with the same decoration as {@link #call}, and let the
     * failure out.
     *
     * <p>Everything the caller needs to classify the failure survives: a
     * {@code FeignException} carrying the status a downstream answered with
     * (see {@link #answeredStatus}), Feign's {@code RetryableException} (status
     * -1) when nothing answered, a {@code CallNotPermittedException} when the
     * circuit opened between attempts.
     *
     * <p>When NO attempt could have reached the service -- the circuit was
     * open ({@code CallNotPermittedException}), the rate limiter refused
     * ({@code RequestNotPermitted}), or the connection could not even be opened
     * or the load balancer had no address for the service
     * ({@link NetworkFailures#neverArrived}), on every attempt -- the failure is
     * thrown as a {@link NothingSentException} with the last one as its cause,
     * so the caller can say truthfully that the service did nothing.
     */
    public <T> T callOrThrow(String name, Supplier<T> supplier) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
        Retry retry = retryRegistry.retry(name);
        RateLimiter rl = rateLimiterRegistry.rateLimiter(name);

        // Set by any attempt that got past the limiter and the breaker and did
        // not fail before its connection was open. An attempt the limiter or
        // the breaker stops never runs this supplier at all.
        AtomicBoolean mayHaveArrived = new AtomicBoolean();
        Supplier<T> attempt = () -> {
            boolean neverArrived = false;
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                neverArrived = NetworkFailures.neverArrived(e);
                throw e;
            } finally {
                if (!neverArrived) {
                    mayHaveArrived.set(true);
                }
            }
        };

        Supplier<T> decorated = Decorators.ofSupplier(attempt)
                .withRateLimiter(rl)
                .withCircuitBreaker(cb)
                .withRetry(retry)
                .decorate();
        try {
            return decorated.get();
        } catch (RuntimeException e) {
            // Logged here so that every failure is recorded with its circuit
            // state exactly once, whether the caller goes on to swallow it or
            // to turn it into a status.
            log.warn("{} failed after R4J decoration (cb={}, retries={}): {}",
                    name, cb.getState(), retry.getMetrics().getNumberOfTotalCalls(), e.getMessage());
            if (!mayHaveArrived.get()) {
                throw new NothingSentException(name, e);
            }
            throw e;
        }
    }

    /**
     * The HTTP status a downstream <strong>answered</strong> with, or
     * {@code -1} if it never answered.
     *
     * <p>The distinction a caller cannot make without this: "reachable and
     * refusing" is not retryable and usually means <em>we</em> sent something
     * wrong, while "never answered" is transient and is worth a 503 with a
     * {@code Retry-After}. Flattening both into one failure is how a checkout
     * bug ends up advertised to shoppers as somebody else's outage.
     *
     * <p>Walks the cause chain rather than checking the top-level type, since a
     * decorator or a mapper may have wrapped the original.
     */
    public static int answeredStatus(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof FeignException fe) {
                // Feign uses -1 itself for "no response", so this needs no
                // separate sentinel.
                return fe.status();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return -1;
    }

    /** The {@code "type"} member of a problem+json body. */
    private static final java.util.regex.Pattern PROBLEM_TYPE =
            java.util.regex.Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * WHAT a refusing downstream said, not just that it refused: the problem
     * {@code type} of its problem+json body, or empty when there is no answer,
     * no body, or no type.
     *
     * <p>Lets a caller pass on the one refusal the shopper can act on -- shipping's
     * {@code outside-delivery-area} -- while every other 4xx stays "we sent
     * something wrong". Keyed on the type rather than the status, because a 422
     * means different things from different services.
     *
     * <p>A pattern match rather than a JSON parse: the bodies come from Oriah's
     * own services, rendered by the shared {@code OriahExceptionHandlerSupport},
     * and the only member read is a URI. Anything that does not match is
     * simply "no type", which callers already treat as an ordinary refusal.
     */
    public static java.util.Optional<java.net.URI> answeredProblemType(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof FeignException fe) {
                return fe.responseBody()
                        .map(b -> java.nio.charset.StandardCharsets.UTF_8.decode(b.duplicate()).toString())
                        .map(PROBLEM_TYPE::matcher)
                        .filter(java.util.regex.Matcher::find)
                        .map(m -> java.net.URI.create(m.group(1)));
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return java.util.Optional.empty();
    }
}
