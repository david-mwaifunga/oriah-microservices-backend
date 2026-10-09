package com.oriah.gatewayserver.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.oriah.gatewayserver.controller.FallbackController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Circuit breaker, fallback and retry.
 *
 * <p>Eureka is disabled in this slice, so {@code lb://catalog} cannot be
 * resolved — which turns out to be the ideal way to test a circuit breaker
 * without a stub downstream: every storefront request fails at the load
 * balancer, the breaker catches it, and the fallback is what comes back. A
 * route whose breaker is misconfigured returns a raw 503 or 500 from the
 * gateway instead, so the two outcomes are easy to tell apart.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs",
        "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false"
})
class ResilienceTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        this.client = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                // The breaker's TimeLimiter is 4s and retry adds two more
                // attempts with backoff, so the worst case is ~12s. The default
                // 5s WebTestClient timeout would fire first and report a
                // timeout rather than the fallback.
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    // ------------------------------------------------------------------
    // The fallback endpoint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the fallback answers 503 with an RFC 7807 body")
    void fallbackReturnsProblemDetail() {
        client.get().uri(FallbackController.FALLBACK_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Service Unavailable")
                // The course returns a plain string here, which oriah-web's
                // typed client would surface as a schema mismatch rather than a
                // readable message.
                .jsonPath("$.type").isEqualTo("https://oriah.dev/problems/service-unavailable")
                .jsonPath("$.detail").exists();
    }

    @ParameterizedTest(name = "{0} /contactSupport is answered")
    @DisplayName("the fallback answers every method, not just GET")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void fallbackAnswersEveryMethod(String method) {
        // The breaker forwards here regardless of the original verb. Restricting
        // this to GET would turn a failed POST into a 405 -- a response that is
        // both wrong and misleading, since it blames the caller's method rather
        // than reporting that the backend is down.
        client.method(HttpMethod.valueOf(method)).uri(FallbackController.FALLBACK_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("the fallback is reachable without a token")
    void fallbackNeedsNoToken() {
        // It is what an ANONYMOUS storefront request gets when catalog is down.
        // A 401 here would replace a useful message with a misleading one, and
        // the course's config leaves it open only because it has no terminal
        // anyExchange rule at all -- ours permits it explicitly.
        client.get().uri(FallbackController.FALLBACK_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("the fallback echoes the correlation id, so a screenshot is actionable")
    void fallbackCarriesCorrelationId() {
        // Driven through a STOREFRONT ROUTE, not by calling /contactSupport
        // directly, because those are not the same journey.
        //
        // GlobalFilters run only for requests that MATCH A ROUTE. A direct call
        // to /contactSupport is served by the gateway's own controller and
        // never passes through RequestTraceFilter, so it has no correlation id
        // to echo -- a first version of this test asserted on that path and
        // failed for exactly that reason. Reached via the breaker the id IS
        // present, because the original request was routed, the filter ran, and
        // `forward:` stays within the same exchange and so keeps its
        // attributes. That is also the only path a real user takes here.
        client.get().uri("/api/v1/products")
                .header(com.oriah.gatewayserver.filter.FilterUtility.CORRELATION_ID, "support-ticket-id")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.correlationId").isEqualTo("support-ticket-id");
    }

    @Test
    @DisplayName("a direct call to the fallback still answers, just without an id")
    void directFallbackCallHasNoCorrelationId() {
        // Documents the consequence of the above rather than treating it as a
        // defect: nothing routes to /contactSupport, so there is no id to echo.
        // It must still answer though -- a monitoring probe or a curious user
        // hitting it directly should get the problem body, not a 500.
        client.get().uri(FallbackController.FALLBACK_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.correlationId").doesNotExist();
    }

    // ------------------------------------------------------------------
    // The breaker actually routes there
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} falls back when its service is unreachable")
    @DisplayName("storefront routes reach the fallback instead of failing raw")
    @ValueSource(strings = {
            "/api/v1/products",
            "/api/v1/search?q=aurelia",
            "/api/v1/products/11111111-1111-1111-1111-111111111111/reviews"
    })
    void storefrontRoutesFallBack(String path) {
        // Nothing is registered in Eureka here, so lb:// resolution fails. With
        // the breaker wired this surfaces as the fallback body; without it, as a
        // raw gateway error. Asserting on the problem `type` is what separates
        // the two -- a raw 503 would not carry it.
        client.get().uri(path)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://oriah.dev/problems/service-unavailable");
    }

    @Test
    @DisplayName("authenticated routes do NOT fall back - they must fail honestly")
    void authenticatedRoutesDoNotFallBack() {
        // Deliberately no breaker on these. A breaker on /api/v1/checkout/**
        // would turn a payment failure into a cheerful "try again shortly"
        // while the caller has no way to know whether the charge went through.
        // Unauthenticated here, so the expected outcome is 401 -- what matters
        // is that it is NOT the fallback's 503.
        client.get().uri("/api/v1/checkout/preview")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------------------
    // Retry policy: the two properties that make it safe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("retry applies to GET only, so a retried write cannot double an order")
    void retryIsGetOnly() {
        RetryGatewayFilterFactory.RetryConfig config = new RetryGatewayFilterFactory.RetryConfig();
        ResilienceConfig.storefrontRetry(config);

        assertThat(config.getMethods())
                .as("retrying a POST at the edge can place two orders; only checkout "
                        + "has an idempotency key, so a retried write elsewhere is a "
                        + "duplicate write")
                .containsExactly(HttpMethod.GET);
    }

    @Test
    @DisplayName("retry applies to 5xx only, so a 4xx is not re-sent three times")
    void retryIsServerErrorOnly() {
        RetryGatewayFilterFactory.RetryConfig config = new RetryGatewayFilterFactory.RetryConfig();
        ResilienceConfig.storefrontRetry(config);

        assertThat(config.getSeries())
                .as("a 4xx is permanently invalid; retrying it amplifies a typo in a "
                        + "query parameter into three requests with backoff. This is the "
                        + "direct reason the Tier 0.5 error-contract fix had to land first "
                        + "- until then every framework error rendered as 500, so a "
                        + "malformed request WAS retry-eligible")
                .containsExactly(HttpStatus.Series.SERVER_ERROR);
    }

    @Test
    @DisplayName("the retry budget is bounded and small")
    void retryBudgetIsBounded() {
        RetryGatewayFilterFactory.RetryConfig config = new RetryGatewayFilterFactory.RetryConfig();
        ResilienceConfig.storefrontRetry(config);

        // The course pairs 3 retries with a 10s ceiling and no overall deadline,
        // so a slow downstream consumes ~4x the intended budget.
        assertThat(config.getRetries())
                .as("total attempts must be %d, including the first", ResilienceConfig.MAX_ATTEMPTS)
                .isEqualTo(ResilienceConfig.MAX_ATTEMPTS - 1);
    }

    @Test
    @DisplayName("the call timeout is not equal to any response timeout, avoiding a deadline race")
    void callTimeoutDoesNotRaceAResponseTimeout() {
        // The course sets its TimeLimiter and httpclient.response-timeout both
        // to 10s. Equal deadlines mean which fires is a race, and they produce
        // different outcomes: the TimeLimiter trips the breaker and reaches the
        // fallback, the response timeout surfaces a raw gateway error. So the
        // same request fails two different ways on consecutive attempts.
        //
        // This gateway sets no httpclient response-timeout, leaving the
        // TimeLimiter as the single authority. Asserted here so that adding one
        // carelessly later is caught.
        String responseTimeout = context.getEnvironment()
                .getProperty("spring.cloud.gateway.server.webflux.httpclient.response-timeout");

        assertThat(responseTimeout)
                .as("if a response-timeout is introduced it must be comfortably larger "
                        + "than the %s TimeLimiter, never equal to it", ResilienceConfig.CALL_TIMEOUT)
                .isNull();
    }
}
