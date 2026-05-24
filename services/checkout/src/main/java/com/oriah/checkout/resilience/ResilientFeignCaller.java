package com.oriah.checkout.resilience;

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
 * <p>{@code name} is both the bean lookup key (every Feign client has
 * a matching {@code resilience4j.{circuitbreaker,retry,ratelimiter}.
 * instances.<name>} block in {@code application.yaml}) and the
 * log/span tag for the call.
 *
 * <p>If the decorated call throws (CB open, retry budget exhausted,
 * rate limiter starved, downstream error), the supplied
 * {@code fallback} is returned. This preserves the graceful-
 * degradation semantic CheckoutService had before, where a failed
 * promotion/shipping/payment call surfaces as a null in the saga
 * branch.
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

    public <T> T call(String name, Supplier<T> supplier, T fallback) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
        Retry retry = retryRegistry.retry(name);
        RateLimiter rl = rateLimiterRegistry.rateLimiter(name);

        Supplier<T> decorated = Decorators.ofSupplier(supplier)
                .withRateLimiter(rl)
                .withCircuitBreaker(cb)
                .withRetry(retry)
                .decorate();
        try {
            return decorated.get();
        } catch (Exception e) {
            log.warn("{} failed after R4J decoration (cb={}, retries={}): {}",
                    name, cb.getState(), retry.getMetrics().getNumberOfTotalCalls(), e.getMessage());
            return fallback;
        }
    }
}
