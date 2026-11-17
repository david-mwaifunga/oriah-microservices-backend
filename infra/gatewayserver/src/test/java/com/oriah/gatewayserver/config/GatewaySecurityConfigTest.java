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
            "/api/v1/shipping/quote",
            // Storefront-LOOKING but not public: the sweep flagged this and
            // POST /shipping/quote (it was /shipping/rates before Phase 7) as the
            // two most likely to be wrongly permitAll'd here. A promotion is now
            // applied by itself and priced per piece, so there is no code a
            // shopper types; the path stays here because it must never become
            // public by accident.
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
            // Phase 2: the real path, with an id, the way the supplier screens call it.
            "/api/v1/admin/procurement/suppliers,                                       ROLE_PROCUREMENT_ADMIN",
            "/api/v1/admin/procurement/suppliers/00000000-0000-0000-0000-000000000001,  ROLE_PROCUREMENT_ADMIN",
            "/api/v1/admin/shipping/towns,                                               ROLE_SHIPPING_ADMIN",
            "/api/v1/admin/finance/expenses,                                             ROLE_FINANCE_ADMIN",
            "/api/v1/admin/finance/contact-info,                                         ROLE_FINANCE_ADMIN",
            "/api/v1/admin/costs/lots,                                                   ROLE_COST_VIEWER",
            "/api/v1/admin/inventory/write-offs,                                         ROLE_STOCK_WRITE_OFF",
            // Phase 6: boxes set aside for a product are INVENTORY_ADMIN's ("box commitments"), under the inventory prefix.
            "/api/v1/admin/inventory/box-commitments,                                    ROLE_INVENTORY_ADMIN",
            "/api/v1/admin/inventory/box-commitments/00000000-0000-0000-0000-000000000001, ROLE_INVENTORY_ADMIN",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/dispatch,         ROLE_FULFILMENT_AGENT",
            // Phase 8: the packer's work list is served by order from a controller of its own, at an EXACT path: nothing else under /orders/dispatch/.
            "/api/v1/admin/orders/dispatch/queue,                                        ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/delivery-waiver,  ROLE_DELIVERY_WAIVER",
            "/api/v1/admin/orders/recorded,                                              ROLE_SALES_AGENT",
            "/api/v1/admin/orders/recorded/preview, ROLE_SALES_AGENT",
            "/api/v1/admin/orders/recorded/boxes, ROLE_SALES_AGENT",
            "/api/v1/admin/orders/recorded/delivery-places, ROLE_SALES_AGENT",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001, ROLE_SALES_AGENT",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/receipt, ROLE_SALES_AGENT",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/cancel, ROLE_SALES_AGENT",
            "/api/v1/admin/orders/late-recorded,                                         ROLE_BACKDATE_SALE",
            "/api/v1/admin/payments/confirmations,                                       ROLE_SALES_AGENT",
            // Phase 7: the two lists the people who confirm money work from are served by ORDER (it owns the data) and are SALES_AGENT's; a box
            // changed after payment is ORDER_ADMIN's (held by a manager and the owner, not by sales).
            "/api/v1/admin/orders/awaiting-payment,                                      ROLE_SALES_AGENT",
            "/api/v1/admin/orders/settlements,                                           ROLE_SALES_AGENT",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/box-swaps,        ROLE_ORDER_ADMIN",
            // Phase 7: rejecting a false payment claim is the staff's, like confirming (a claim is rejected by its id, which the awaiting-payment list shows).
            "/api/v1/admin/payments/claims,                                              ROLE_SALES_AGENT",
            "/api/v1/admin/payments/claims/00000000-0000-0000-0000-000000000001/reject,  ROLE_SALES_AGENT",
            // Money held for a customer (paid for something that could not be sold) is the owner's: PAYMENT_ADMIN.
            "/api/v1/admin/payments/held-money,                                          ROLE_PAYMENT_ADMIN"
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
            "/api/v1/admin/procurement/suppliers",
            "/api/v1/admin/procurement/suppliers/00000000-0000-0000-0000-000000000001",
            "/api/v1/admin/shipping/towns",
            "/api/v1/admin/finance/expenses",
            "/api/v1/admin/finance/contact-info",
            "/api/v1/admin/costs/lots",
            "/api/v1/admin/inventory/write-offs",
            "/api/v1/admin/inventory/box-commitments",
            "/api/v1/admin/pricing/below-cost",
            "/api/v1/admin/pricing/variants/00000000-0000-0000-0000-000000000001",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/dispatch",
            "/api/v1/admin/orders/dispatch/queue",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/delivery-waiver",
            "/api/v1/admin/orders/recorded",
            "/api/v1/admin/orders/recorded/preview",
            "/api/v1/admin/orders/recorded/boxes",
            "/api/v1/admin/orders/recorded/delivery-places",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/receipt",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/cancel",
            "/api/v1/admin/orders/late-recorded",
            "/api/v1/admin/payments/confirmations",
            "/api/v1/admin/orders/awaiting-payment",
            "/api/v1/admin/orders/settlements",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/box-swaps",
            "/api/v1/admin/payments/claims",
            "/api/v1/admin/payments/claims/00000000-0000-0000-0000-000000000001/reject",
            "/api/v1/admin/payments/held-money"
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
            "/api/v1/admin/orders/dispatch/queue,                                        ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/dispatch/queue,                                        ROLE_SALES_AGENT",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/delivery-waiver,  ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded,                                              ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded/preview, ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded/preview, ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/recorded/boxes, ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded/boxes, ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/recorded/delivery-places, ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded/delivery-places, ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001, ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001, ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/receipt, ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/receipt, ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/cancel, ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/recorded/00000000-0000-0000-0000-000000000001/cancel, ROLE_FULFILMENT_AGENT",
            "/api/v1/admin/orders/late-recorded,                                         ROLE_ORDER_ADMIN",
            // A sale recorded late is the owner's call (section 8.2), not every
            // sales assistant's.
            "/api/v1/admin/orders/late-recorded,                                         ROLE_SALES_AGENT",
            "/api/v1/admin/payments/confirmations,                                       ROLE_PAYMENT_ADMIN",
            // Phase 7: the sales queues are SALES_AGENT's alone (ORDER_ADMIN guards the rest of the prefix), and the box swap is ORDER_ADMIN's alone
            // (a sales person is not given it).
            "/api/v1/admin/orders/awaiting-payment,                                      ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/settlements,                                           ROLE_ORDER_ADMIN",
            "/api/v1/admin/orders/00000000-0000-0000-0000-000000000001/box-swaps,        ROLE_SALES_AGENT",
            "/api/v1/admin/payments/claims,                                              ROLE_PAYMENT_ADMIN",
            "/api/v1/admin/payments/claims/00000000-0000-0000-0000-000000000001/reject,  ROLE_PAYMENT_ADMIN",
            // And the other way: the staff who confirm money never see the money held for customers.
            "/api/v1/admin/payments/held-money,                                          ROLE_SALES_AGENT"
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
    @DisplayName("a packer (DISPATCH) reaches the work list, the slip and the steps and nothing else (correcting a recipient or address is ORDER_ADMIN's): no orders list, box swap, waiver, payment, customer, cost or stock, "
            + "and nothing else under /orders/dispatch/ (an unknown path there falls to ORDER_ADMIN and fails closed)")
    void aPackerReachesOnlyTheDispatchWork() {
        assertAdmitted(as("DISPATCH").get().uri("/api/v1/admin/orders/dispatch/queue"));
        assertAdmitted(as("DISPATCH").get().uri(AN_ORDER + "/dispatch"));
        assertAdmitted(as("DISPATCH").post().uri(AN_ORDER + "/dispatch/pack"));
        assertAdmitted(as("DISPATCH").post().uri(AN_ORDER + "/dispatch/ship"));
        assertAdmitted(as("DISPATCH").post().uri(AN_ORDER + "/dispatch/deliver"));
        for (String path : java.util.List.of("/api/v1/admin/orders", AN_ORDER, AN_ORDER + "/box-swaps", AN_ORDER + "/delivery-waiver", AN_ORDER + "/delivery-details",
                "/api/v1/admin/orders/dispatch/anything-else", "/api/v1/admin/orders/dispatch", "/api/v1/admin/payments/confirmations",
                "/api/v1/admin/customers", "/api/v1/admin/costs/lots", "/api/v1/admin/inventory", "/api/v1/admin/pricing/below-cost")) {
            as("DISPATCH").get().uri(path).exchange().expectStatus().isForbidden();
        }
        as("DISPATCH").post().uri(AN_ORDER + "/transition").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("MANAGER runs the shop, but cannot write off stock or record a sale late")
    void aManagerCannotWriteOffOrBackdate() {
        as("MANAGER").get().uri("/api/v1/admin/inventory/write-offs").exchange().expectStatus().isForbidden();
        as("MANAGER").get().uri("/api/v1/admin/orders/late-recorded").exchange().expectStatus().isForbidden();
        as("MANAGER").get().uri("/api/v1/admin/finance/expenses").exchange().expectStatus().isForbidden();

        assertAdmitted(as("MANAGER").post().uri(AN_ORDER + "/transition"));
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/procurement/consignments"));
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/procurement/suppliers"));
        assertAdmitted(as("MANAGER").get().uri(AN_ORDER + "/dispatch"));
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/costs/lots"));
    }

    @ParameterizedTest(name = "{0} cannot sell stock by hand")
    @DisplayName("consuming reserved stock for a sale is the order system's act: the gateway refuses it to every person, owner included")
    @ValueSource(strings = {"OWNER", "MANAGER", "SALES", "DISPATCH", "BOOKKEEPER"})
    void nobodyCanSellStockByHand(String jobRole) {
        // Draining stock books cost of goods against a sale line. If a MANAGER (who holds INVENTORY_ADMIN) could call it with
        // an invented sale line, the owner-only write-off rule (Q8) would be a formality. The service call stays: the order
        // system's machine identity calls inventory directly (service to service), never through the gateway.
        String confirm = "/api/v1/admin/inventory/00000000-0000-0000-0000-000000000001/confirm";

        as(jobRole).post().uri(confirm).exchange().expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{0} cannot hold, sell, release or swap a box through the gateway")
    @DisplayName("an order's hold, its sale, its release and a box swap are the order system's acts: the gateway refuses them to every person, owner included")
    @ValueSource(strings = {"OWNER", "MANAGER", "SALES", "DISPATCH", "BOOKKEEPER"})
    void nobodyCanHoldOrSwapByHand(String jobRole) {
        // MANAGER and OWNER hold INVENTORY_ADMIN, which opens the rest of /api/v1/admin/inventory/**. A hold with no owner cannot be let go
        // exactly once, and a sale books cost of goods: both are the order system's, which calls inventory directly (service to service).
        String id = "00000000-0000-0000-0000-000000000001";

        as(jobRole).post().uri("/api/v1/admin/inventory/reservations").exchange().expectStatus().isForbidden();
        as(jobRole).get().uri("/api/v1/admin/inventory/reservations/" + id).exchange().expectStatus().isForbidden();
        as(jobRole).post().uri("/api/v1/admin/inventory/reservations/" + id + "/consume").exchange().expectStatus().isForbidden();
        as(jobRole).post().uri("/api/v1/admin/inventory/reservations/" + id + "/release").exchange().expectStatus().isForbidden();
        as(jobRole).post().uri("/api/v1/admin/inventory/reservations/" + id + "/extend").exchange().expectStatus().isForbidden();
        as(jobRole).post().uri("/api/v1/admin/inventory/box-swaps").exchange().expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{0} cannot reverse a sale or decide a returned piece through the gateway")
    @DisplayName("Phase 12: putting a paid sale's stock back and deciding what became of a returned piece are the order system's acts: the gateway refuses them to every person, owner included")
    @ValueSource(strings = {"OWNER", "MANAGER", "SALES", "DISPATCH", "BOOKKEEPER"})
    void nobodyReversesASaleByHand(String jobRole) {
        // MANAGER and OWNER hold INVENTORY_ADMIN, which opens the rest of /api/v1/admin/inventory/**. A reversal moves stock AND cost of goods for a
        // sale order has corrected; done by hand it would leave the order paid and its stock back. Order calls inventory directly (service to service).
        String id = "00000000-0000-0000-0000-000000000001";

        as(jobRole).post().uri("/api/v1/admin/inventory/sale-reversals").exchange().expectStatus().isForbidden();
        as(jobRole).get().uri("/api/v1/admin/inventory/sale-reversals/" + id).exchange().expectStatus().isForbidden();
        as(jobRole).post().uri("/api/v1/admin/inventory/returned-pieces/" + id + "/outcome").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Phase 12: refunds are the owner's: what is owed, the money check, paying out and voiding a payout need PAYMENT_ADMIN, which only OWNER holds")
    void onlyTheOwnerPaysRefunds() {
        String id = "00000000-0000-0000-0000-000000000001";
        String[] paths = {"/api/v1/admin/payments/refunds-due", "/api/v1/admin/payments/refunds-due/" + id, "/api/v1/admin/payments/payouts",
                "/api/v1/admin/payments/payouts/" + id};
        for (String path : paths) {
            assertAdmitted(as("OWNER").get().uri(path));
            for (String jobRole : new String[] {"MANAGER", "SALES", "DISPATCH", "BOOKKEEPER"}) {
                as(jobRole).get().uri(path).exchange().expectStatus().isForbidden();
            }
        }
        assertAdmitted(as("OWNER").post().uri("/api/v1/admin/payments/payouts"));
        assertAdmitted(as("OWNER").post().uri("/api/v1/admin/payments/payouts/" + id + "/void"));
        assertAdmitted(as("OWNER").post().uri("/api/v1/admin/payments/refunds-due/" + id + "/money-check"));
        for (String jobRole : new String[] {"MANAGER", "SALES", "DISPATCH", "BOOKKEEPER"}) {
            as(jobRole).post().uri("/api/v1/admin/payments/payouts").exchange().expectStatus().isForbidden();
            as(jobRole).post().uri("/api/v1/admin/payments/payouts/" + id + "/void").exchange().expectStatus().isForbidden();
            as(jobRole).post().uri("/api/v1/admin/payments/refunds-due/" + id + "/money-check").exchange().expectStatus().isForbidden();
        }
    }

    @Test
    @DisplayName("Phase 12: cancelling a paid order, recording and reading returns, assessing a piece, linking an exchange and abandoning a correction are ORDER_ADMIN's: the owner and the manager, never sales, a packer or the bookkeeper")
    void correctionsAreTheOrderAdministrators() {
        String id = "00000000-0000-0000-0000-000000000001";
        String[] posts = {AN_ORDER + "/cancellation", AN_ORDER + "/returns", "/api/v1/admin/orders/returns/" + id + "/pieces/" + id + "/assessment",
                "/api/v1/admin/orders/returns/" + id + "/exchange", "/api/v1/admin/orders/corrections/" + id + "/abandon"};
        String[] gets = {AN_ORDER + "/cancellation", "/api/v1/admin/orders/returns", "/api/v1/admin/orders/returns/" + id, "/api/v1/admin/orders/corrections"};
        for (String jobRole : new String[] {"OWNER", "MANAGER"}) {
            for (String path : posts) {
                assertAdmitted(as(jobRole).post().uri(path));
            }
            for (String path : gets) {
                assertAdmitted(as(jobRole).get().uri(path));
            }
        }
        for (String jobRole : new String[] {"SALES", "DISPATCH", "BOOKKEEPER"}) {
            for (String path : posts) {
                as(jobRole).post().uri(path).exchange().expectStatus().isForbidden();
            }
            for (String path : gets) {
                as(jobRole).get().uri(path).exchange().expectStatus().isForbidden();
            }
        }
        // The marked-down listing is the catalogue's own door: a manager (CATALOG_ADMIN) reaches it, a salesperson does not.
        assertAdmitted(as("MANAGER").post().uri("/api/v1/admin/products/" + id + "/variants/marked-down"));
        as("SALES").post().uri("/api/v1/admin/products/" + id + "/variants/marked-down").exchange().expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{0} cannot reach a service's internal reads through the gateway")
    @DisplayName("/internal/** is service to service only: the gateway refuses it to every person, owner included, so a promotion list that needs no token is never public")
    @ValueSource(strings = {"OWNER", "MANAGER", "SALES", "DISPATCH", "BOOKKEEPER"})
    void internalReadsAreNeverPublic(String jobRole) {
        // Catalog reads what promotions are in force from promotion directly, with no token (it prices anonymously). That is only safe
        // because the edge never carries it: no route exists, and this rule keeps it that way if one is ever added by mistake.
        as(jobRole).get().uri("/internal/promotions/in-force").exchange().expectStatus().isForbidden();
        as(jobRole).get().uri("/internal/anything/else").exchange().expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{0} cannot reach catalog's internal variant reads through the gateway")
    @DisplayName("naming variants and pricing a sale at a past date are service to service: no gateway route, and the unmatched-admin rule refuses every person, owner included")
    @ValueSource(strings = {"OWNER", "MANAGER", "SALES", "DISPATCH", "BOOKKEEPER"})
    void internalVariantReadsAreNeverPublic(String jobRole) {
        // The order system forwards the signed-in person's own token to catalog, directly. SALES_AGENT is checked in catalog; the edge must
        // never be the way in, or a browser could price a "sale" at any past date.
        as(jobRole).post().uri("/api/v1/admin/variants/pricing-as-of").exchange().expectStatus().isForbidden();
        as(jobRole).post().uri("/api/v1/admin/variants/lookup").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("the rest of the inventory admin area stays with INVENTORY_ADMIN: only holds, sales and swaps are walled off")
    void restOfInventoryAdminIsUnchanged() {
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/inventory"));
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/inventory/reconciliation"));
        assertAdmitted(as("MANAGER").get().uri("/api/v1/admin/inventory/box-commitments"));
    }

    @ParameterizedTest(name = "{0} cannot reach the suppliers")
    @DisplayName("SALES, DISPATCH and BOOKKEEPER get 403 on every supplier call: a supplier's contact and payment details are procurement's")
    @ValueSource(strings = {"SALES", "DISPATCH", "BOOKKEEPER"})
    void staffWithoutProcurementCannotReachTheSuppliers(String jobRole) {
        String supplier = "/api/v1/admin/procurement/suppliers/00000000-0000-0000-0000-000000000001";

        as(jobRole).get().uri("/api/v1/admin/procurement/suppliers").exchange().expectStatus().isForbidden();
        as(jobRole).post().uri("/api/v1/admin/procurement/suppliers").exchange().expectStatus().isForbidden();
        as(jobRole).get().uri(supplier).exchange().expectStatus().isForbidden();
        as(jobRole).patch().uri(supplier).exchange().expectStatus().isForbidden();
        as(jobRole).delete().uri(supplier).exchange().expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{0} cannot reach finance's contact-info")
    @DisplayName("MANAGER and SALES get 403 on finance's contact-info: only FINANCE_ADMIN holders (the owner, the bookkeeper) are admitted")
    @ValueSource(strings = {"MANAGER", "SALES"})
    void staffWithoutFinanceCannotReachFinanceContactInfo(String jobRole) {
        as(jobRole).get().uri("/api/v1/admin/finance/contact-info").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("PATCH and DELETE on a supplier are admitted for procurement, so the edit and the correction are not blocked at the edge")
    void procurementCanEditAndDeleteSuppliersAtTheEdge() {
        String supplier = "/api/v1/admin/procurement/suppliers/00000000-0000-0000-0000-000000000001";

        assertAdmitted(as("MANAGER").patch().uri(supplier));
        assertAdmitted(as("MANAGER").delete().uri(supplier));
        assertAdmitted(as("OWNER").post().uri("/api/v1/admin/procurement/suppliers"));
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

    // ------------------------------------------------------------------
    // Phase 6: pricing needs BOTH CATALOG_ADMIN and COST_VIEWER
    // ------------------------------------------------------------------

    private static final String PRICING_LIST = "/api/v1/admin/pricing/below-cost";
    private static final String PRICING_READ = "/api/v1/admin/pricing/variants/00000000-0000-0000-0000-000000000001";
    private static final String PRICING_WRITE = PRICING_READ + "/price";

    private WebTestClient holding(String... authorities) {
        return client.mutateWith(mockJwt().authorities(java.util.Arrays.stream(authorities).map(GatewaySecurityConfigTest::authority)
                .toArray(SimpleGrantedAuthority[]::new)));
    }

    @Test
    @DisplayName("the pricing screen shows landed cost, so it needs CATALOG_ADMIN AND COST_VIEWER: a person holding both is admitted")
    void pricingAdmitsWhoHoldsBoth() {
        for (WebTestClient who : new WebTestClient[] {holding("ROLE_CATALOG_ADMIN", "ROLE_COST_VIEWER"), as("OWNER"), as("MANAGER")}) {
            assertAdmitted(who.get().uri(PRICING_LIST));
            assertAdmitted(who.get().uri(PRICING_READ));
            assertAdmitted(who.put().uri(PRICING_WRITE));
        }
    }

    @Test
    @DisplayName("either role alone is refused at the edge: CATALOG_ADMIN without COST_VIEWER would price blind and reach a cost it may not see, COST_VIEWER alone may not price")
    void pricingRefusesEitherAlone() {
        for (WebTestClient who : new WebTestClient[] {holding("ROLE_CATALOG_ADMIN"), holding("ROLE_COST_VIEWER"), as("BOOKKEEPER"), as("SALES"),
                as("DISPATCH"), holding("ROLE_INVENTORY_ADMIN"), holding("ROLE_USER")}) {
            who.get().uri(PRICING_LIST).exchange().expectStatus().isForbidden();
            who.get().uri(PRICING_READ).exchange().expectStatus().isForbidden();
            who.put().uri(PRICING_WRITE).exchange().expectStatus().isForbidden();
        }
    }

    @Test
    @DisplayName("nobody signed out reaches pricing: 401")
    void pricingNeedsASignIn() {
        client.get().uri(PRICING_LIST).exchange().expectStatus().isUnauthorized();
        client.put().uri(PRICING_WRITE).exchange().expectStatus().isUnauthorized();
    }

    @ParameterizedTest(name = "OWNER reaches {0}")
    @DisplayName("OWNER reaches every capability's work")
    @ValueSource(strings = {
            "/api/v1/admin/products", "/api/v1/admin/inventory", "/api/v1/admin/inventory/write-offs",
            "/api/v1/admin/customers", "/api/v1/admin/orders/recorded", "/api/v1/admin/orders/late-recorded",
            "/api/v1/admin/payments", "/api/v1/admin/payments/confirmations", "/api/v1/admin/procurement/consignments",
            "/api/v1/admin/procurement/suppliers",
            "/api/v1/admin/shipping/towns", "/api/v1/admin/finance/expenses", "/api/v1/admin/costs/lots",
            "/api/v1/admin/inventory/box-commitments", "/api/v1/admin/pricing/below-cost"
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

    // ---- Phase 11: the two staff reads of notification, and its machine identity at the edge ----------------------------------------------------

    private static final String STAFF_ALERTS = "/api/v1/admin/notifications/alerts";
    private static final String STAFF_MESSAGES = "/api/v1/admin/notifications/messages";

    @Test
    @DisplayName("the alerts read admits sales staff, and whoever may see costs and set prices; the messages read is sales staff's alone")
    void theStaffReadsOfNotification() {
        for (WebTestClient who : new WebTestClient[] {holding("ROLE_SALES_AGENT"), holding("ROLE_CATALOG_ADMIN", "ROLE_COST_VIEWER"), as("OWNER"),
                as("MANAGER"), as("SALES")}) {
            assertAdmitted(who.get().uri(STAFF_ALERTS));
        }
        for (WebTestClient who : new WebTestClient[] {holding("ROLE_COST_VIEWER"), holding("ROLE_CATALOG_ADMIN"), holding("ROLE_USER"), as("DISPATCH"),
                as("BOOKKEEPER")}) {
            who.get().uri(STAFF_ALERTS).exchange().expectStatus().isForbidden();
        }
        for (WebTestClient who : new WebTestClient[] {holding("ROLE_SALES_AGENT"), as("OWNER"), as("SALES")}) {
            assertAdmitted(who.get().uri(STAFF_MESSAGES + "?status=BY_HAND"));
        }
        for (WebTestClient who : new WebTestClient[] {holding("ROLE_CATALOG_ADMIN", "ROLE_COST_VIEWER"), holding("ROLE_USER"), as("DISPATCH")}) {
            who.get().uri(STAFF_MESSAGES + "?status=BY_HAND").exchange().expectStatus().isForbidden();
        }
        client.get().uri(STAFF_ALERTS).exchange().expectStatus().isUnauthorized();
        client.get().uri(STAFF_MESSAGES).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("nothing else under that prefix is open, to anyone: another path or another method fails closed")
    void otherNotificationAdminPathsFailClosed() {
        WebTestClient owner = as("OWNER");
        owner.get().uri("/api/v1/admin/notifications/anything-else").exchange().expectStatus().isForbidden();
        owner.get().uri(STAFF_ALERTS + "/00000000-0000-0000-0000-000000000001").exchange().expectStatus().isForbidden();
        owner.post().uri(STAFF_ALERTS).exchange().expectStatus().isForbidden();
        owner.delete().uri(STAFF_MESSAGES).exchange().expectStatus().isForbidden();
    }

    private WebTestClient machine(String username, String... authorities) {
        return client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("preferred_username", username))
                .authorities(java.util.Arrays.stream(authorities).map(GatewaySecurityConfigTest::authority).toArray(SimpleGrantedAuthority[]::new)));
    }

    @Test
    @DisplayName("notification's own machine identity is refused at the edge on EVERY path: it holds CATALOG_ADMIN, which can set prices, and it never needs the gateway")
    void theNotificationMachineIsRefusedOnEveryPath() {
        WebTestClient notification = machine("service-account-oriah-notification", "ROLE_CATALOG_ADMIN", "ROLE_COST_VIEWER");

        notification.get().uri(PRICING_LIST).exchange().expectStatus().isForbidden();
        notification.put().uri(PRICING_WRITE).exchange().expectStatus().isForbidden();
        notification.get().uri(STAFF_ALERTS).exchange().expectStatus().isForbidden();
        notification.post().uri("/api/v1/admin/products").exchange().expectStatus().isForbidden();
        notification.get().uri("/api/v1/products").exchange().expectStatus().isForbidden();
        notification.get().uri("/api/v1/notifications/me").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("that wall is about WHO, not about roles: a person holding the same two roles still prices, and the order system's machine is untouched")
    void theEdgeWallNamesOneMachineOnly() {
        assertAdmitted(machine("maya", "ROLE_CATALOG_ADMIN", "ROLE_COST_VIEWER").get().uri(PRICING_LIST));
        assertAdmitted(machine("service-account-oriah-admin", "ROLE_USER", "ROLE_INVENTORY_ADMIN").get().uri("/api/v1/shipping/towns"));
        assertAdmitted(holding("ROLE_CATALOG_ADMIN", "ROLE_COST_VIEWER").put().uri(PRICING_WRITE));
        assertAdmitted(client.get().uri("/api/v1/products"));
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
