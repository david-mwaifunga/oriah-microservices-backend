package com.oriah.gatewayserver;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Route snapshot — the oracle that lets us move the route table from
 * {@code application.yaml} into a Java-DSL {@code RouteLocator} bean
 * (the section_12 convention) and <em>prove</em> nothing moved.
 *
 * <p>Two independent assertions:
 * <ol>
 *   <li>{@link #routeIdSetIsExactlyTheSnapshot()} pins the full set of
 *       route ids. Catches an accidentally dropped or renamed route —
 *       which is why the DSL migration must use the
 *       {@code .route("<id>", r -> ...)} overload rather than letting
 *       Spring Cloud Gateway generate anonymous ids.</li>
 *   <li>{@link #pathResolvesToExpectedRoute(String, String)} pins the
 *       actual <em>matching behaviour</em>: for a representative path,
 *       which route wins. This is the assertion that matters, because
 *       SCG is first-match-by-order (NOT longest-path-wins), so the
 *       {@code product-reviews-route} only beats {@code
 *       catalog-public-route} for {@code /api/v1/products/{id}/reviews}
 *       because it carries {@code order: -10}. A YAML→DSL migration
 *       that forgets the order silently reroutes reviews into catalog
 *       and 404s.</li>
 * </ol>
 *
 * <p>We resolve routes through the {@link RouteLocator} bean rather than
 * the {@code /actuator/gateway/routes} endpoint because that endpoint is
 * gated by default in Spring Cloud Gateway 5.0.x (see the note in
 * {@link GatewayServerApplicationTests}). Going via the bean is also
 * stronger: it survives any actuator exposure change.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // issuer-uri in application.yaml triggers eager OIDC discovery against
        // Keycloak at context startup. Overriding with a jwk-set-uri makes the
        // decoder lazy, so this test needs no live Keycloak -- the same
        // override the 12 services' tests use.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs",
        // RedisRateLimiter wires beans but needs no live Redis as long as we
        // never dispatch a request through a rate-limited route; we only
        // evaluate predicates here.
        "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false"
})
class RouteSnapshotTest {

    /**
     * Every route id: 23 API routes plus
     * 13 per-service {@code /v3/api-docs/<svc>} routes that back the
     * aggregated Swagger UI at the gateway.
     */
    private static final List<String> EXPECTED_ROUTE_IDS = List.of(
            // --- API routes ---
            "catalog-public-route",
            "catalog-admin-route",
            "inventory-admin-route",
            "customer-route",
            "customer-admin-route",
            "cart-route",
            "payment-route",
            "order-route",
            "order-admin-route",
            "promotion-route",
            "promotion-admin-route",
            "payment-admin-route",
            // Phase 2: suppliers, and every later procurement endpoint (the
            // gateway already guarded the prefix; nothing was routed to it).
            "procurement-admin-route",
            // Finance: expenses and the books, one admin prefix (FINANCE_ADMIN).
            "finance-admin-route",
            // Phase 5: cost and margin reads (lots, the sale rows of the ledger) live under a prefix of their own,
            // /api/v1/admin/costs/, which the security chain has guarded with COST_VIEWER since Phase 0 and which
            // nothing was routed to until inventory built them.
            "inventory-costs-admin-route",
            // Phase 6: pricing (CATALOG_ADMIN and COST_VIEWER) has a prefix of its own, because /api/v1/admin/products/** is
            // CATALOG_ADMIN alone and the pricing screen shows landed cost.
            "catalog-pricing-admin-route",
            // Phase 7: delivery prices by town and option (SHIPPING_ADMIN). The matcher has guarded the prefix since Phase 0;
            // nothing was routed to it until shipping gained its database and its admin endpoints.
            "shipping-admin-route",
            "shipping-route",
            "checkout-route",
            "notification-route",
            "review-route",
            "product-reviews-route",
            "search-route",
            // Added with the products phase.
            "catalog-taxonomy-route",
            "catalog-variants-route",
            "catalog-categories-admin-route",
            "catalog-images-route",
            // --- OpenAPI aggregation routes ---
            "catalog-docs",
            "inventory-docs",
            "customer-docs",
            "cart-docs",
            "payment-docs",
            "order-docs",
            "promotion-docs",
            "shipping-docs",
            "checkout-docs",
            "notification-docs",
            "review-docs",
            "search-docs",
            "procurement-docs",
            "finance-docs");

    @Autowired
    private RouteLocator routeLocator;

    @Test
    @DisplayName("route id set is exactly the 39-route snapshot")
    void routeIdSetIsExactlyTheSnapshot() {
        List<String> actual = routes().stream().map(Route::getId).toList();

        assertThat(actual)
                .as("route ids (order-independent); a diff here means a route was "
                        + "added, dropped or renamed by the DSL migration")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_ROUTE_IDS);
    }

    @Test
    @DisplayName("every route targets the same lb:// service as the YAML baseline")
    void routeTargetsAreUnchanged() {
        Map<String, String> actual = routes().stream()
                .collect(Collectors.toMap(Route::getId, r -> r.getUri().toString()));

        assertThat(actual)
                .containsEntry("catalog-public-route", "lb://catalog")
                .containsEntry("catalog-admin-route", "lb://catalog")
                .containsEntry("catalog-docs", "lb://catalog")
                .containsEntry("inventory-admin-route", "lb://inventory")
                .containsEntry("inventory-costs-admin-route", "lb://inventory")
                .containsEntry("catalog-pricing-admin-route", "lb://catalog")
                .containsEntry("customer-route", "lb://customer")
                .containsEntry("customer-admin-route", "lb://customer")
                .containsEntry("cart-route", "lb://cart")
                .containsEntry("payment-route", "lb://payment")
                .containsEntry("order-route", "lb://order")
                .containsEntry("order-admin-route", "lb://order")
                .containsEntry("promotion-route", "lb://promotion")
                .containsEntry("promotion-admin-route", "lb://promotion")
                .containsEntry("payment-admin-route", "lb://payment")
                .containsEntry("procurement-admin-route", "lb://procurement")
                .containsEntry("procurement-docs", "lb://procurement")
                .containsEntry("finance-admin-route", "lb://finance")
                .containsEntry("finance-docs", "lb://finance")
                .containsEntry("shipping-route", "lb://shipping")
                .containsEntry("shipping-admin-route", "lb://shipping")
                .containsEntry("checkout-route", "lb://checkout")
                .containsEntry("notification-route", "lb://notification")
                // Both the generic reviews route and the nested
                // product-reviews route live in review-service.
                .containsEntry("review-route", "lb://review")
                .containsEntry("product-reviews-route", "lb://review")
                .containsEntry("search-route", "lb://search")
                // The three products-phase routes, all catalog.
                .containsEntry("catalog-taxonomy-route", "lb://catalog")
                .containsEntry("catalog-variants-route", "lb://catalog")
                .containsEntry("catalog-categories-admin-route", "lb://catalog")
                .containsEntry("catalog-images-route", "lb://catalog");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("path resolves to the same route as the YAML baseline (first-match by order)")
    @CsvSource({
            // Plain storefront + admin paths.
            "/api/v1/products,                                   catalog-public-route",
            "/api/v1/products/11111111-1111-1111-1111-111111111111, catalog-public-route",
            "/api/v1/admin/products,                             catalog-admin-route",
            "/api/v1/admin/inventory,                            inventory-admin-route",
            "/api/v1/admin/costs/lots,                           inventory-costs-admin-route",
            "/api/v1/admin/costs/movements,                      inventory-costs-admin-route",
            "/api/v1/admin/pricing/below-cost,                   catalog-pricing-admin-route",
            "/api/v1/admin/pricing/variants/11111111-1111-1111-1111-111111111111/price, catalog-pricing-admin-route",
            "/api/v1/admin/inventory/box-commitments,            inventory-admin-route",
            "/api/v1/customers/me,                               customer-route",
            "/api/v1/admin/customers,                            customer-admin-route",
            "/api/v1/carts/me,                                   cart-route",
            "/api/v1/payments/intents,                           payment-route",
            "/api/v1/orders/me,                                  order-route",
            "/api/v1/admin/orders,                               order-admin-route",
            "/api/v1/promotions/WELCOME15,                        promotion-route",
            "/api/v1/admin/promotions,                           promotion-admin-route",
            "/api/v1/admin/payments,                             payment-admin-route",
            // Every procurement path, the suppliers list, one supplier, and the
            // contact-info endpoint, under the one prefix. The gateway guarded
            // the prefix from Phase 0 and routed none of it until Phase 2.
            "/api/v1/admin/procurement/suppliers,                procurement-admin-route",
            "/api/v1/admin/procurement/suppliers/11111111-1111-1111-1111-111111111111, procurement-admin-route",
            "/api/v1/admin/procurement/contact-info,             procurement-admin-route",
            "/api/v1/admin/finance/contact-info,                 finance-admin-route",
            "/api/v1/shipping/quote,                             shipping-route",
            "/api/v1/shipping/towns,                             shipping-route",
            "/api/v1/admin/shipping/towns,                       shipping-admin-route",
            "/api/v1/admin/shipping/prices/11111111-1111-1111-1111-111111111111/publish, shipping-admin-route",
            "/api/v1/checkout/preview,                           checkout-route",
            "/api/v1/notifications/me,                           notification-route",
            "/api/v1/reviews/me,                                 review-route",
            "/api/v1/search,                                     search-route",
            // Products phase. The taxonomy and variant paths are siblings of
            // the catalog route rather than nested under it, so no ordering
            // trick is needed -- but they are pinned so a future broadening of
            // /api/v1/products/** cannot quietly swallow them.
            "/api/v1/categories,                                 catalog-taxonomy-route",
            "/api/v1/categories/rings,                           catalog-taxonomy-route",
            "/api/v1/product-types,                              catalog-taxonomy-route",
            "/api/v1/variants/pricing,                           catalog-variants-route",
            "/api/v1/admin/categories,                           catalog-categories-admin-route",
            // Image bytes must NOT resolve to catalog-public-route, whose
            // rate limiter and circuit breaker would throttle a product grid
            // and hand an <img> tag a problem+json document.
            "/api/v1/product-images/11111111-1111-1111-1111-111111111111, catalog-images-route",
            // THE precedence case: nested product reviews must beat the
            // catalog /api/v1/products/** route via order: -10.
            "/api/v1/products/11111111-1111-1111-1111-111111111111/reviews, product-reviews-route",
            "/api/v1/products/11111111-1111-1111-1111-111111111111/reviews/aggregate, product-reviews-route",
            // OpenAPI aggregation.
            "/v3/api-docs/catalog,                               catalog-docs",
            "/v3/api-docs/checkout,                              checkout-docs",
            "/v3/api-docs/search,                                search-docs",
            "/v3/api-docs/procurement,                           procurement-docs",
            "/v3/api-docs/finance,                               finance-docs"
    })
    void pathResolvesToExpectedRoute(String path, String expectedRouteId) {
        assertThat(firstMatchingRouteId(path))
                .as("SCG resolves by ascending route order, first match wins")
                .isEqualTo(expectedRouteId);
    }

    @Test
    @DisplayName("no route proxies /actuator/** to a downstream service")
    void actuatorIsNotProxiedDownstream() {
        // Deliberate divergence from section_12, which sets
        // management.endpoints.web.exposure.include: "*" everywhere. We keep a
        // curated exposure list AND refuse to route actuator traffic through
        // the edge, so a downstream service's actuator is not reachable from
        // outside the compose network.
        assertThat(firstMatchingRouteId("/actuator/prometheus")).isNull();
        assertThat(firstMatchingRouteId("/actuator/health")).isNull();
        assertThat(firstMatchingRouteId("/actuator/env")).isNull();
    }

    /** Routes sorted the way SCG's handler mapping sorts them: ascending order. */
    private List<Route> routes() {
        List<Route> routes = routeLocator.getRoutes()
                .collectList()
                .block();
        assertThat(routes).as("RouteLocator returned no routes").isNotNull().isNotEmpty();
        return routes.stream()
                .sorted(Comparator.comparingInt(Route::getOrder))
                .toList();
    }

    /**
     * Mirrors {@code RoutePredicateHandlerMapping}: walk routes in ascending
     * order, return the id of the first whose predicate accepts the exchange,
     * or {@code null} when nothing matches.
     */
    private String firstMatchingRouteId(String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path.trim()).build());
        for (Route route : routes()) {
            Boolean matched = Mono.from(route.getPredicate().apply(exchange))
                    .defaultIfEmpty(false)
                    .onErrorReturn(false)
                    .block();
            if (Boolean.TRUE.equals(matched)) {
                return route.getId();
            }
        }
        return null;
    }
}
