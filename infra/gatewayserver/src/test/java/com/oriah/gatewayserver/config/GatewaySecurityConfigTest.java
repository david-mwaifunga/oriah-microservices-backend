package com.oriah.gatewayserver.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * The gateway's authorization rules.
 *
 * <p>These assertions are derived from a sweep of all 12 services' actual
 * rules, not from what the gateway "ought" to allow — because the gateway's job
 * here is to <em>mirror</em> them. A gateway rule stricter than a service's
 * breaks a currently-working call; one looser creates a false sense of
 * protection. Two findings from that sweep shaped this test:
 *
 * <ul>
 *   <li>For 8 of the 12 services the real rules live in {@code @PreAuthorize}
 *       on controllers, not in {@code SecurityConfig}. Reading only the
 *       SecurityConfig files would have produced a gateway looser than reality
 *       by one role check per service.</li>
 *   <li>{@code /api/v1/admin/promotions/**} is guarded by
 *       {@code CATALOG_ADMIN}, not a {@code PROMOTION_ADMIN} role — no such
 *       role exists in the realm. A convention mapping
 *       {@code /admin/<svc>/** → <SVC>_ADMIN} would be wrong there, and would
 *       invent a guard for payments (where {@code PAYMENT_ADMIN} exists in the
 *       realm but guards nothing).</li>
 * </ul>
 *
 * <p>Requests here are not proxied anywhere: Eureka is disabled, so a permitted
 * request fails to route and surfaces as 5xx/404. That is fine — what is under
 * test is whether the security chain ADMITTED the request, so the assertions
 * check only that the outcome is not 401/403.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false",
        // A resource server needs an issuer or a JWK set to start. Point it at
        // a URL that is never dialled: every test here supplies a synthetic
        // token through mockJwt(), which bypasses decoding entirely.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"
})
class GatewaySecurityConfigTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        // Bound to the ApplicationContext rather than a live port, and with the
        // springSecurity() configurer applied. Both matter: mockJwt() injects a
        // pre-authenticated token into the security context, which only works
        // against the mock server chain -- against a real port the request
        // would have to carry an actual signed JWT, which would mean running
        // Keycloak to test a routing table.
        this.client = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    // ------------------------------------------------------------------
    // Deny by default
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} requires a token")
    @DisplayName("every API path rejects an unauthenticated request")
    @ValueSource(strings = {
            "/api/v1/customers/me",
            "/api/v1/carts/me",
            "/api/v1/orders/me",
            "/api/v1/payments/intents",
            "/api/v1/notifications/me",
            "/api/v1/reviews/me",
            "/api/v1/checkout/preview",
            "/api/v1/shipping/rates",
            // Storefront-LOOKING but not public: the sweep flagged this and
            // POST /shipping/rates as the two most likely to be wrongly
            // permitAll'd here. promotion guards it with hasRole('USER').
            "/api/v1/promotions/WELCOME15",
            "/api/v1/admin/products",
            "/api/v1/admin/inventory",
            "/api/v1/admin/customers",
            "/api/v1/admin/orders",
            "/api/v1/admin/promotions",
            "/api/v1/admin/payments"
    })
    void rejectsUnauthenticatedRequests(String path) {
        client.get().uri(path).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("an unmatched path is denied rather than silently allowed")
    void unmatchedPathIsDenied() {
        // Spring Security WebFlux grants access when NO matcher matches, so a
        // config without a terminal anyExchange rule leaves every unlisted path
        // open. The course's config has exactly that gap.
        client.get().uri("/some/unmapped/path").exchange().expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------------------
    // Public storefront reads
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "GET {0} is public")
    @DisplayName("anonymous storefront reads stay reachable without a token")
    @ValueSource(strings = {
            "/api/v1/products",
            "/api/v1/products/11111111-1111-1111-1111-111111111111",
            "/api/v1/products/11111111-1111-1111-1111-111111111111/reviews",
            "/api/v1/products/11111111-1111-1111-1111-111111111111/reviews/aggregate",
            "/api/v1/search?q=aurelia"
    })
    void permitsAnonymousStorefrontReads(String path) {
        assertAdmitted(client.get().uri(path));
    }

    @Test
    @DisplayName("POST to a public reviews path still requires a token")
    void reviewsAreReadableButNotWritableAnonymously() {
        // review is the only service where the same path has different auth per
        // method: GET is public, POST needs USER. So the public rule has to be
        // METHOD-scoped, or posting a review becomes anonymous.
        client.post().uri("/api/v1/products/11111111-1111-1111-1111-111111111111/reviews")
                .exchange().expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------------------
    // Infrastructure paths
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is reachable without a token")
    @DisplayName("probes, metrics and the aggregated API docs stay tokenless")
    @ValueSource(strings = {
            // Orchestrators probe these, and Prometheus scrapes with no
            // credentials on the compose network -- requiring a token here
            // would silently stop monitoring rather than fail loudly.
            "/actuator/health",
            "/actuator/health/readiness",
            "/actuator/health/liveness",
            "/actuator/info",
            "/actuator/prometheus",
            // The gateway's own swagger-ui fetches each service's spec through
            // these; a token requirement breaks its dropdown.
            "/v3/api-docs/catalog",
            "/swagger-ui.html"
    })
    void permitsInfrastructurePaths(String path) {
        assertAdmitted(client.get().uri(path));
    }

    @Test
    @DisplayName("CORS preflight is answered without a token")
    void permitsCorsPreflight() {
        // A browser sends OPTIONS with no Authorization header. If that is
        // rejected the actual request is never sent, and the failure shows up
        // in the browser as an opaque CORS error rather than a 401.
        assertAdmitted(client.method(HttpMethod.OPTIONS).uri("/api/v1/carts/me")
                .header("Origin", "http://localhost:4000")
                .header("Access-Control-Request-Method", "GET"));
    }

    // ------------------------------------------------------------------
    // Role enforcement at the edge
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} needs {1}")
    @DisplayName("admin paths require the role the owning service actually checks")
    @CsvSource({
            "/api/v1/admin/products,    ROLE_CATALOG_ADMIN",
            "/api/v1/admin/inventory,   ROLE_INVENTORY_ADMIN",
            "/api/v1/admin/customers,   ROLE_CUSTOMER_ADMIN",
            "/api/v1/admin/orders,      ROLE_ORDER_ADMIN",
            // NOT PROMOTION_ADMIN. promotion's AdminPromotionController is
            // guarded by hasRole('CATALOG_ADMIN'), so CATALOG_ADMIN spans two
            // services.
            "/api/v1/admin/promotions,  ROLE_CATALOG_ADMIN",
            // PAYMENT_ADMIN's first use. The realm has declared it since the
            // beginning and nothing guarded on it, because the endpoint it
            // was minted for did not exist until now.
            "/api/v1/admin/payments,    ROLE_PAYMENT_ADMIN"
    })
    void adminPathsRequireTheCorrectRole(String path, String requiredAuthority) {
        assertAdmitted(client
                .mutateWith(mockJwt().authorities(authority(requiredAuthority.trim())))
                .get().uri(path.trim()));
    }

    @ParameterizedTest(name = "{0} rejects a plain USER")
    @DisplayName("a user without the admin role is forbidden, not merely unauthenticated")
    @ValueSource(strings = {
            "/api/v1/admin/products",
            "/api/v1/admin/inventory",
            "/api/v1/admin/customers",
            "/api/v1/admin/orders",
            "/api/v1/admin/promotions",
            "/api/v1/admin/payments"
    })
    void adminPathsRejectNonAdmins(String path) {
        client.mutateWith(mockJwt().authorities(authority("ROLE_USER")))
                .get().uri(path)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("an authenticated user reaches the non-admin API")
    void authenticatedUserReachesTheApi() {
        // The gateway requires only a valid token on these; each service
        // remains the authority on the hasRole('USER') check. Enforcing USER
        // here too would make the edge stricter than the services, which is the
        // failure mode that breaks a working call.
        assertAdmitted(client.mutateWith(mockJwt().authorities(authority("ROLE_USER")))
                .get().uri("/api/v1/carts/me"));
    }

    private static SimpleGrantedAuthority authority(String name) {
        return new SimpleGrantedAuthority(name);
    }

    /**
     * Asserts the security chain ADMITTED the request. Nothing is routable in
     * this slice (Eureka is off), so a permitted request ends in 404/5xx; the
     * only outcomes that matter here are 401 and 403.
     */
    private static void assertAdmitted(WebTestClient.RequestHeadersSpec<?> request) {
        request.exchange().expectStatus().value(status -> {
            if (status == 401 || status == 403) {
                throw new AssertionError("request was rejected by the security chain with " + status
                        + "; expected it to be admitted (routing failure afterwards is fine)");
            }
        });
    }
}
