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
            "/api/v1/search?q=aurelia",
            // Added with the products phase: the category navigation, the type
            // definitions the product page builds its spec table from, and the
            // image bytes. All three are read by a visitor who could not
            // possibly hold a token yet.
            "/api/v1/categories",
            "/api/v1/categories/rings",
            "/api/v1/product-types",
            "/api/v1/product-images/11111111-1111-1111-1111-111111111111"
    })
    void permitsAnonymousStorefrontReads(String path) {
        assertAdmitted(client.get().uri(path));
    }

    @Test
    @DisplayName("variant pricing is anonymous, because cart has no token to relay")
    void variantPricingIsPublic() {
        // A POST, so PUBLIC_GET_PATHS cannot carry it -- that array is
        // deliberately GET-scoped. It reads nothing but prices, which are
        // already on the public product page, and cart-service calls it while
        // serving an anonymous shopper's bag.
        assertAdmitted(client.post().uri("/api/v1/variants/pricing"));
    }

    @Test
    @DisplayName("the pricing permit is scoped to that exact path, not to every variant write")
    void variantPricingPermitIsNarrow() {
        // The permit names /api/v1/variants/pricing rather than
        // /api/v1/variants/**, so a future write under that prefix is not
        // swept in by it. This is the assertion that would fail if someone
        // broadened the matcher for convenience.
        client.post().uri("/api/v1/variants/11111111-1111-1111-1111-111111111111")
                .exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/v1/variants/11111111-1111-1111-1111-111111111111")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("admin category writes require CATALOG_ADMIN, not merely a token")
    void adminCategoriesRequireCatalogAdmin() {
        // This test replaces one that asserted only "anonymous gets 401".
        // That assertion was worthless here: anyExchange().authenticated()
        // gives a 401 to every unmatched path, so it passed while the
        // categories matcher was entirely MISSING and a plain USER token was
        // being admitted at the edge. The DisplayName claimed role enforcement
        // the body never checked.
        client.post().uri("/api/v1/admin/categories")
                .exchange().expectStatus().isUnauthorized();

        client.mutateWith(mockJwt().authorities(authority("ROLE_USER")))
                .post().uri("/api/v1/admin/categories")
                .exchange().expectStatus().isForbidden();

        assertAdmitted(client.mutateWith(mockJwt().authorities(authority("ROLE_CATALOG_ADMIN")))
                .post().uri("/api/v1/admin/categories"));
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
            "/api/v1/admin/payments,    ROLE_PAYMENT_ADMIN",
            // Phase 0, item 6: where each new capability's work lives, fixed
            // before the endpoints exist. Each building phase adds the route
            // and the service's own method-level check.
            "/api/v1/admin/procurement/consignments,                                    ROLE_PROCUREMENT_ADMIN",
            "/api/v1/admin/shipping/towns,                                               ROLE_SHIPPING_ADMIN",
            "/api/v1/admin/finance/expenses,                                             ROLE_FINANCE_ADMIN",
            "/api/v1/admin/costs/lots,                                                   ROLE_COST_VIEWER",
            "/api/v1/admin/inventory/write-offs,                                         ROLE_STOCK_WRITE_OFF",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/dispatch,         ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/delivery-waiver,  ROLE_DELIVERY_WAIVER",
            "/api/v1/admin/orders/recorded,                                              ROLE_SALES_AGENT",
            "/api/v1/admin/orders/late-recorded,                                         ROLE_BACKDATE_SALE",
            "/api/v1/admin/payments/confirmations,                                       ROLE_SALES_AGENT"
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
            "/api/v1/admin/payments",
            "/api/v1/admin/categories",
            "/api/v1/admin/procurement/consignments",
            "/api/v1/admin/shipping/towns",
            "/api/v1/admin/finance/expenses",
            "/api/v1/admin/costs/lots",
            "/api/v1/admin/inventory/write-offs",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/dispatch",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/delivery-waiver",
            "/api/v1/admin/orders/recorded",
            "/api/v1/admin/orders/late-recorded",
            "/api/v1/admin/payments/confirmations"
    })
    void adminPathsRejectNonAdmins(String path) {
        client.mutateWith(mockJwt().authorities(authority("ROLE_USER")))
                .get().uri(path)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ------------------------------------------------------------------
    // Capabilities and job roles (MVP_PLAN Phase 0, item 6)
    // ------------------------------------------------------------------

    private static final String AN_ORDER = "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001";

    /**
     * A token as Keycloak issues it to a person holding this job role: the
     * job role's capabilities, composites expanded, each as ROLE_X. Built from
     * the same map RealmRolesTest pins against the realm file, so the two
     * cannot drift apart.
     */
    private WebTestClient as(String jobRole) {
        SimpleGrantedAuthority[] authorities = RealmRolesTest.JOB_ROLES.get(jobRole).stream()
                .map(capability -> authority("ROLE_" + capability))
                .toArray(SimpleGrantedAuthority[]::new);
        return client.mutateWith(mockJwt().authorities(authorities));
    }

    @ParameterizedTest(name = "{0} is not opened by {1} alone")
    @DisplayName("a narrower capability's path is not opened by the broader role of the area it sits in")
    @CsvSource({
            // Each of these sits under a prefix a broader role guards. Without
            // a matcher BEFORE that prefix's, the broader role would be
            // admitted -- a manager writing off stock, an order admin
            // back-dating a sale.
            "/api/v1/admin/inventory/write-offs,                                         ROLE_INVENTORY_ADMIN",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/dispatch,         ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/delivery-waiver,  ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded,                                              ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/late-recorded,                                         ROLE_ORDER_ADMIN",
            // A sale recorded late is the owner's call (section 8.2), not every
            // sales assistant's.
            "/api/v1/admin/orders/late-recorded,                                         ROLE_SALES_AGENT",
            "/api/v1/admin/payments/confirmations,                                       ROLE_PAYMENT_ADMIN"
    })
    void aNarrowerPathIsNotOpenedByTheBroaderRole(String path, String broaderAuthority) {
        client.mutateWith(mockJwt().authorities(authority(broaderAuthority.trim())))
                .get().uri(path.trim())
                .exchange()
                .expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{0} may not drive an order's status")
    @DisplayName("SALES and DISPATCH get 403 on the generic order transition -- the plan's Phase 0 test")
    @ValueSource(strings = {"SALES", "DISPATCH"})
    void salesAndDispatchCannotDriveTheGenericTransition(String jobRole) {
        // The generic transition stays ORDER_ADMIN. Dispatch and payment
        // confirmation get endpoints of their own, so a packer can dispatch
        // without being able to mark any order paid or cancelled.
        as(jobRole).post().uri(AN_ORDER + "/transition")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("MANAGER runs the shop, but cannot write off stock or record a sale late")
    void aManagerCannotWriteOffOrBackdate() {
        as("MANAGER").get().uri("/api/v1/admin/inventory/write-offs").exchange().expectStatus().isForbidden();
        as("MANAGER").get().uri("/api/v1/admin/orders/late-recorded").exchange().expectStatus().isForbidden();
        as("MANAGER").get().uri("/api/v1/admin/finance/expenses").exchange().expectStatus().isForbidden();

        assertAdmitted(as("MANAGER").post().uri(AN_ORDER + "/transition"));
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/procurement/consignments"));
        assertAdmitted(as("MANAGER").get().uri(AN_ORDER + "/dispatch"));
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/costs/lots"));
    }

    @Test
    @DisplayName("BOOKKEEPER sees the books and costs, and nothing that changes stock, prices or orders")
    void aBookkeeperSeesTheBooksOnly() {
        assertAdmitted(as("BOOKKEEPER").get().uri("/api/v1/admin/finance/expenses"));
        assertAdmitted(as("BOOKKEEPER").get().uri("/api/v1/admin/costs/lots"));

        as("BOOKKEEPER").get().uri("/api/v1/admin/products").exchange().expectStatus().isForbidden();
        as("BOOKKEEPER").get().uri("/api/v1/admin/inventory").exchange().expectStatus().isForbidden();
        as("BOOKKEEPER").post().uri(AN_ORDER + "/transition").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("SALES records sales and confirms payments, and sees no cost or margin (Q29)")
    void salesRecordsAndConfirmsButSeesNoCost() {
        assertAdmitted(as("SALES").get().uri("/api/v1/admin/orders/recorded"));
        assertAdmitted(as("SALES").get().uri("/api/v1/admin/payments/confirmations"));

        as("SALES").get().uri("/api/v1/admin/costs/lots").exchange().expectStatus().isForbidden();
        as("SALES").get().uri("/api/v1/admin/payments").exchange().expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "OWNER reaches {0}")
    @DisplayName("OWNER reaches every capability's work")
    @ValueSource(strings = {
            "/api/v1/admin/products", "/api/v1/admin/inventory", "/api/v1/admin/inventory/write-offs",
            "/api/v1/admin/customers", "/api/v1/admin/orders/recorded", "/api/v1/admin/orders/late-recorded",
            "/api/v1/admin/payments", "/api/v1/admin/payments/confirmations", "/api/v1/admin/procurement/consignments",
            "/api/v1/admin/shipping/towns", "/api/v1/admin/finance/expenses", "/api/v1/admin/costs/lots"
    })
    void theOwnerReachesEverything(String path) {
        assertAdmitted(as("OWNER").get().uri(path));
    }

    @ParameterizedTest(name = "{0} is denied even to OWNER")
    @DisplayName("an admin path with no matcher of its own is DENIED, not admitted to any signed-in user")
    @ValueSource(strings = {"/api/v1/admin/refunds", "/api/v1/admin/something-new/x", "/api/v1/admin"})
    void anUnmappedAdminPathIsDenied(String path) {
        // Before item 6 the chain ended in anyExchange().authenticated(), so a
        // new admin prefix without a matcher was open to every shopper -- which
        // happened once, to categories. Now a forgotten matcher fails CLOSED:
        // nobody gets in until the phase that builds it says who may.
        as("OWNER").get().uri(path).exchange().expectStatus().isForbidden();
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
