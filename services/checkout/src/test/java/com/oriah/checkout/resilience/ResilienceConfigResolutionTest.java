package com.oriah.checkout.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves checkout still starts, and still has working resilience settings, when
 * the Config Server is unavailable.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The shared Resilience4j numbers moved to
 * {@code config-repo/application.yaml} as
 * {@code resilience4j.{circuitbreaker,retry,ratelimiter}.configs.default}, and
 * this service's instances were reduced to
 * {@code <name>: { base-config: default }}.
 *
 * <p>That raises a question the move itself does not answer. checkout imports
 * configuration with {@code spring.config.import:
 * "optional:configserver:..."} — <em>optional</em>, so it is designed to boot
 * when the Config Server is down. If {@code configs.default} lives only in
 * config-repo, then in that situation every instance references a base config
 * that does not exist. Two possible outcomes, and they are very different:
 *
 * <ul>
 *   <li>a loud {@code ConfigurationNotFoundException} at startup — bad, because
 *       centralizing shared values would have converted an optional dependency
 *       into a hard one, which is a <em>reduction</em> in resilience from a
 *       change made in the name of resilience; or</li>
 *   <li>a silent fall back to Resilience4j's own built-in defaults — worse,
 *       because those differ materially (sliding window 100 rather than 10,
 *       wait-in-open 60s rather than 30s) and nothing would say so.</li>
 * </ul>
 *
 * <p>So the behaviour is pinned here rather than reasoned about. The test runs
 * with {@code spring.cloud.config.enabled=false}, which is exactly the
 * Config-Server-is-down case.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // The scenario under test: no Config Server, so nothing supplies
        // resilience4j.*.configs.default from config-repo.
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"
})
class ResilienceConfigResolutionTest {

    /** Every Feign client checkout orchestrates. */
    private static final String[] CLIENTS =
            {"cart", "catalog", "customer", "promotion", "shipping", "payment", "order"};

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Test
    @DisplayName("the context starts at all without the Config Server")
    void contextStartsWithoutConfigServer() {
        // If base-config resolution were fatal, this test would never reach its
        // body -- the failure would be a context-load error.
        assertThat(circuitBreakerRegistry).isNotNull();
        assertThat(retryRegistry).isNotNull();
        assertThat(rateLimiterRegistry).isNotNull();
    }

    @ParameterizedTest(name = "{0} has a circuit breaker")
    @DisplayName("every Feign client still gets its own breaker instance")
    @ValueSource(strings = {"cart", "catalog", "customer", "promotion", "shipping", "payment", "order"})
    void everyClientHasItsOwnBreaker(String client) {
        // One instance per client, not per method: when a downstream is
        // unhealthy every call from this orchestrator to it should trip the
        // SAME circuit.
        assertThat(circuitBreakerRegistry.circuitBreaker(client)).isNotNull();
        assertThat(retryRegistry.retry(client)).isNotNull();
        assertThat(rateLimiterRegistry.rateLimiter(client)).isNotNull();
    }

    @Test
    @DisplayName("breakers are not silently left on Resilience4j's built-in defaults")
    void breakersDoNotSilentlyFallBackToLibraryDefaults() {
        // This is the assertion that would catch the dangerous outcome. The
        // library's own defaults are slidingWindowSize=100 and
        // waitDurationInOpenState=60s; ours are 10 and 30s. If config-repo's
        // configs.default is unreachable AND resolution degrades silently,
        // these would come back as 100/60s and every breaker in the system
        // would be ten times less sensitive than intended, with nothing saying
        // so.
        //
        // Documents whichever behaviour is real: see the assertion message if
        // this ever fires.
        var config = circuitBreakerRegistry.circuitBreaker("cart").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize())
                .as("sliding window: 10 means our settings resolved; 100 means the "
                        + "instance silently fell back to Resilience4j's built-in default, "
                        + "which is the failure mode this test exists to catch")
                .isEqualTo(10);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
                .as("wait-in-open: 30s is ours, 60s is the library default")
                .isEqualTo(30_000L);
    }

    @Test
    @DisplayName("retry still refuses to retry a 4xx")
    void retryStillIgnoresClientErrors() {
        // The single most important property of the retry config, and the one
        // that would be lost first if base-config resolution degraded: a
        // malformed body or a failed auth check will not improve on the second
        // attempt, and retrying it turns one client mistake into three requests.
        var config = retryRegistry.retry("cart").getRetryConfig();

        assertThat(config.getMaxAttempts())
                .as("3 attempts is ours; the library default is also 3, so this alone "
                        + "does not prove resolution -- the exception predicate below does")
                .isEqualTo(3);

        var feignClientError = new feign.FeignException.FeignClientException(
                400, "bad request", feign.Request.create(
                        feign.Request.HttpMethod.GET, "http://cart/api/v1/carts/me",
                        java.util.Map.of(), null, null, null),
                null, java.util.Map.of());

        assertThat(config.getExceptionPredicate().test(feignClientError))
                .as("a 4xx must NOT be retried. If this is true, either "
                        + "ignore-exceptions did not resolve or someone removed it")
                .isFalse();
    }
}
