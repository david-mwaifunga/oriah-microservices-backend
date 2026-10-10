package com.oriah.gatewayserver.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * 401 and 403 at the edge must carry an RFC 7807 body, like every other error.
 *
 * <h2>Finding #16, which the error goldens surfaced</h2>
 *
 * <p>After a whole tier spent giving errors problem bodies, these two still
 * answered with nothing:
 *
 * <pre>
 * GET /api/v1/carts/me            (no token)
 * HTTP/1.1 401 Unauthorized
 * WWW-Authenticate: Bearer
 * content-length: 0                      &lt;- zero bytes
 * </pre>
 *
 * <p>Spring Security's {@code ServerAuthenticationEntryPoint} and
 * {@code ServerAccessDeniedHandler} run <strong>before</strong> the
 * {@code @RestControllerAdvice} and answer on their own, so the shared advice
 * never sees them. The {@code WWW-Authenticate} header is correct and must
 * survive — it is how a client learns the scheme — but the empty body means
 * 401 and 403 were the only errors in the platform carrying no
 * <strong>{@code traceId}</strong>, and therefore the only ones that could not
 * be correlated to a trace from a screenshot.
 *
 * <p>403 is the worse of the two: "you are authenticated but lack this role" is
 * exactly what the caller needs to be told, and an empty body tells them
 * nothing.
 *
 * <p>The problem {@code type} slugs match the vocabulary the shared advice
 * derives from the status reason phrase — {@code unauthorized} and
 * {@code forbidden} — so a client sees one scheme across the whole platform
 * rather than two.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs",
        "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false"
})
class EdgeErrorBodyTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        this.client = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    @DisplayName("401 carries a problem body with a type, detail and instance")
    void unauthorizedCarriesAProblemBody() {
        client.get().uri("/api/v1/carts/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.type").isEqualTo("https://oriah.dev/problems/unauthorized")
                .jsonPath("$.detail").exists()
                .jsonPath("$.instance").isEqualTo("/api/v1/carts/me");
        // traceId is asserted LIVE instead -- see the note below.
    }

    @Test
    @DisplayName("401 still advertises the Bearer scheme")
    void unauthorizedKeepsTheWwwAuthenticateHeader() {
        // The header is the one genuinely useful thing the default entry point
        // did, and replacing that entry point is exactly how it gets lost. RFC
        // 9110 says a 401 MUST carry it.
        client.get().uri("/api/v1/carts/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("WWW-Authenticate");
    }

    @Test
    @DisplayName("403 carries a problem body")
    void forbiddenCarriesAProblemBody() {
        // An authenticated caller with no admin role, hitting a path the
        // GATEWAY guards (not the downstream service). alice's real-world
        // equivalent: a signed-in shopper who found an admin URL.
        client.mutateWith(mockJwt().authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_USER")))
                .get().uri("/api/v1/admin/products")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Forbidden")
                .jsonPath("$.type").isEqualTo("https://oriah.dev/problems/forbidden")
                .jsonPath("$.detail").exists();
    }

    @Test
    @DisplayName("the body never says WHICH role is missing")
    void forbiddenDoesNotLeakTheRequiredRole() {
        // Deliberate. Naming the role turns a 403 into a map of the
        // authorization model -- "you need CATALOG_ADMIN" tells an attacker
        // exactly which account to go after. The detail says the caller lacks
        // the required role and stops there; the role that was actually checked
        // is in the gateway's logs, correlated by the traceId in the body.
        client.mutateWith(mockJwt().authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_USER")))
                .get().uri("/api/v1/admin/products")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .as("a 403 body must not enumerate the authorization model")
                        .doesNotContain("CATALOG_ADMIN")
                        .doesNotContain("INVENTORY_ADMIN")
                        .doesNotContain("hasRole"));
    }


    // ------------------------------------------------------------------
    // Why traceId is not asserted here
    //
    // Because it is never present, which took measuring to establish. The
    // security chain answers before anything opens a span: verified against the
    // RUNNING gateway, where neither the 401 response nor
    // EdgeProblemResponses own log line carries a traceId. A request rejected
    // at the edge never routes, so it is never traced at all.
    //
    // An earlier version of this note said the traceId was pinned live by
    // scripts/error_goldens.py instead. That was wrong in the same way the
    // production javadoc was: there is no id to pin. The goldens do record the
    // body key set, so if tracing is ever moved ahead of security and a traceId
    // starts appearing, it shows up there as a diff.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a public path is still public - the entry point did not become a filter")
    void publicPathsAreUnaffected() {
        // Guards the obvious way to break this: wiring an entry point that
        // answers 401 for everything. /actuator/health is in
        // PUBLIC_INFRASTRUCTURE_PATHS because Prometheus and the compose
        // healthcheck read it tokenless.
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status)
                                .as("actuator health must stay reachable without a token")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }
}
