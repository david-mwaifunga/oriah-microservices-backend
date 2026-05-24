package com.oriah.checkout.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Unit tests for the synchronous R4J decoration. We do not need a
 * Spring context: instantiate the registries with realistic configs,
 * hand them to the caller, and verify the supplier wiring.
 */
class ResilientFeignCallerTest {

    private final RetryConfig retryConfig = RetryConfig.<Object>custom()
            .maxAttempts(3)
            .retryExceptions(IOException.class, UncheckedIOException.class)
            .build();

    private final ResilientFeignCaller caller = new ResilientFeignCaller(
            CircuitBreakerRegistry.ofDefaults(),
            RetryRegistry.of(retryConfig),
            RateLimiterRegistry.of(RateLimiterConfig.ofDefaults()));

    @Test
    void successfulSupplierReturnsItsValue() {
        String result = caller.call("test1", () -> "ok", "fallback");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void failingSupplierReturnsTheFallback() {
        String result = caller.call("test2",
                () -> { throw new RuntimeException("boom"); },
                "fallback-value");
        assertThat(result).isEqualTo("fallback-value");
    }

    @Test
    void retryableExceptionIsRetriedUpToMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        String result = caller.call("test3", () -> {
            attempts.incrementAndGet();
            throw new UncheckedIOException(new IOException("transient"));
        }, "after-retries");

        assertThat(result).isEqualTo("after-retries");
        // maxAttempts = 3 means 1 initial + 2 retries = 3 calls total
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void retryRecoversIfALaterAttemptSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = caller.call("test4", () -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                throw new UncheckedIOException(new IOException("transient"));
            }
            return "third-try-charm";
        }, "fallback");

        assertThat(result).isEqualTo("third-try-charm");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void runsOnTheCallingThread() {
        // Critical for the SecurityContext ThreadLocal: the supplier
        // must execute on the same thread that invoked call(), NOT
        // on a ForkJoinPool worker (which is what Spring Cloud's
        // OpenFeign+R4J integration does and which originally broke
        // JWT propagation -- see ResilientFeignCaller class javadoc).
        Thread callingThread = Thread.currentThread();
        Thread[] supplierThread = new Thread[1];

        caller.call("test5", () -> {
            supplierThread[0] = Thread.currentThread();
            return "ok";
        }, "fallback");

        assertThat(supplierThread[0]).isSameAs(callingThread);
    }
}
