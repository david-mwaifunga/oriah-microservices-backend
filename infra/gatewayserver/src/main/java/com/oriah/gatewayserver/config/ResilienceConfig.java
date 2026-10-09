package com.oriah.gatewayserver.config;

import java.time.Duration;

import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

/**
 * Circuit-breaker, time-limiter and retry settings for the edge.
 *
 * <p>Expressed in code rather than {@code resilience4j.*} YAML so the numbers
 * sit next to the reasoning for them, which matters here because the course's
 * equivalent configuration contains two latent problems that are invisible in
 * YAML.
 *
 * <h2>Problem 1: the deadline race</h2>
 *
 * <p>The course sets its Resilience4J {@code TimeLimiter} to 10s and the
 * gateway's own {@code httpclient.response-timeout} to 10s as well. Two
 * deadlines with the same value means which one fires is a race, and the two
 * produce different outcomes: the TimeLimiter trips the breaker and reaches the
 * fallback, while the response timeout surfaces as a raw gateway error. The
 * same request can therefore fail two different ways on consecutive attempts.
 *
 * <p>Here the TimeLimiter is {@link #CALL_TIMEOUT} and the gateway sets NO
 * httpclient response-timeout at all, so the TimeLimiter is the single
 * authority on how long a downstream call may take. If a response-timeout is
 * ever added it must be comfortably larger than this value, not equal to it.
 *
 * <h2>Problem 2: retry multiplies the budget</h2>
 *
 * <p>The course combines 3 retries and exponential backoff with that same 10s
 * ceiling and no overall deadline, so a slow downstream can consume roughly
 * four times the intended budget. The numbers here are chosen so the worst case
 * is bounded and small enough to state:
 *
 * <pre>
 *   attempt 1        up to 4s   (TimeLimiter)
 *   backoff             50ms
 *   attempt 2        up to 4s
 *   backoff            100ms
 *   attempt 3        up to 4s
 *   ------------------------------
 *   worst case      ~12.15s before the fallback
 * </pre>
 *
 * <p>Which is the cost of {@link #MAX_ATTEMPTS} attempts, and the reason that
 * number is 3 rather than the 4 the course effectively uses.
 */
@Configuration
public class ResilienceConfig {

    /**
     * How long one downstream call may take before the breaker counts it as a
     * failure. Deliberately short: these are storefront reads, and a visitor
     * waiting 10s for a product list has already left.
     */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(4);

    /** Total attempts, including the first. See the worst-case table above. */
    public static final int MAX_ATTEMPTS = 3;

    /** First backoff; doubles each attempt. */
    public static final Duration BACKOFF_FIRST = Duration.ofMillis(50);

    /** Ceiling on any single backoff, so the doubling cannot run away. */
    public static final Duration BACKOFF_MAX = Duration.ofMillis(500);

    /**
     * Fraction of calls in the sliding window that must fail before the breaker
     * opens. 50% on a window of {@link #SLIDING_WINDOW_SIZE} means a service
     * that is merely flaky stays in use, while one that is genuinely down is
     * cut off quickly.
     */
    public static final float FAILURE_RATE_THRESHOLD = 50.0f;

    public static final int SLIDING_WINDOW_SIZE = 10;

    /** How long the breaker stays open before probing the downstream again. */
    public static final Duration OPEN_STATE_DURATION = Duration.ofSeconds(10);

    /**
     * Default config for every circuit breaker the gateway creates.
     *
     * <p>{@code Customizer} rather than {@code resilience4j.*} properties
     * because the gateway does not read from the Config Server, so these
     * numbers would otherwise live only in this module's YAML — further from
     * the routes that depend on them.
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(SLIDING_WINDOW_SIZE)
                        .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                        .waitDurationInOpenState(OPEN_STATE_DURATION)
                        // Probe with a single request rather than reopening the
                        // floodgates: a service that just came back up should
                        // not be hit with the full retry-amplified load.
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(CALL_TIMEOUT)
                        .build())
                .build());
    }

    /**
     * Retry policy for the storefront GET routes.
     *
     * <p>Exposed as a static method, and unit-tested, because the two things
     * that make it safe are both easy to get wrong and invisible once it is
     * inlined into a route lambda:
     *
     * <ul>
     *   <li><strong>GET only.</strong> Retrying a POST at the edge can place
     *       two orders. The 12 services have no idempotency keys on their write
     *       endpoints except checkout, so a retried write is a duplicate
     *       write.</li>
     *   <li><strong>5xx only.</strong> Retrying a 4xx re-sends a request that
     *       is permanently invalid — three times, with backoff, for a typo in a
     *       query parameter. This is the direct reason Tier 0.5 had to land
     *       first: until then every framework error rendered as 500, so a
     *       malformed request WAS retry-eligible and this policy would have
     *       amplified client mistakes into load.</li>
     * </ul>
     */
    public static void storefrontRetry(RetryGatewayFilterFactory.RetryConfig config) {
        config.setRetries(MAX_ATTEMPTS - 1)
                .setMethods(HttpMethod.GET)
                .setSeries(HttpStatus.Series.SERVER_ERROR)
                .setBackoff(BACKOFF_FIRST, BACKOFF_MAX, 2, true);
    }
}
