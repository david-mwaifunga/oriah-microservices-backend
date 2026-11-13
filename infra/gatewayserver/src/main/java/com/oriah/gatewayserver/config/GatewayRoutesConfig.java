package com.oriah.gatewayserver.config;

import java.util.List;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.Buildable;
import org.springframework.cloud.gateway.route.builder.PredicateSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.oriah.gatewayserver.controller.FallbackController;

/**
 * The gateway route table, in code.
 *
 * <p>This replaces the {@code spring.cloud.gateway.server.webflux.routes}
 * block that used to live in {@code application.yaml}. Same 29 routes, same
 * targets, same precedence — expressed as a {@link RouteLocator} bean so the
 * table is refactorable, searchable and reviewable as Java rather than as 200
 * lines of indented YAML.
 *
 * <p>Deliberate divergence from the course reference, which puts its
 * {@code RouteLocator} directly in the application class: it has three routes,
 * we have 29. Keeping them here leaves the application class as a bootstrapper
 * instead of a 150-line route dump.
 *
 * <h2>Things that will bite whoever edits this</h2>
 *
 * <p><strong>1. Spring Cloud Gateway is first-match-by-order, NOT
 * longest-path.</strong> {@code product-reviews-route} carries
 * {@code order(-10)} purely so it is evaluated before
 * {@code catalog-public-route}, whose {@code /api/v1/products/**} would
 * otherwise swallow {@code /api/v1/products/{id}/reviews} and 404 every nested
 * review. Routes with no explicit order default to 0.
 *
 * <p><strong>2. {@code order(...)} must be the FIRST call in the lambda.</strong>
 * It exists only on {@link PredicateSpec}; the specs returned after
 * {@code path(...)} ({@code BooleanSpec}) and after {@code filters(...)}
 * ({@code GatewayFilterSpec}) do not expose it. {@code .path(x).order(-10)}
 * does not compile.
 *
 * <p><strong>3. Adding a {@code RouteLocator} bean does not replace the YAML —
 * it UNIONS with it.</strong> Gateway's auto-configuration builds a
 * {@code CachingRouteLocator} over a {@code CompositeRouteLocator} of every
 * {@code RouteLocator} bean plus the YAML-backed one. Leaving the YAML routes
 * in place while this bean exists yields 58 routes, not 29, and silently
 * duplicates every route. That is why the YAML block was deleted in the same
 * commit, and why {@code RouteSnapshotTest} asserts the id set exactly.
 *
 * <p><strong>4. The route ids are load-bearing.</strong> Every route uses the
 * {@code route(id, fn)} overload rather than letting Gateway generate an
 * anonymous id, because {@code RouteSnapshotTest} pins the id set, the
 * {@code lb://} targets and path→route resolution. Generated ids would make
 * that test a tautology and the migration unprovable.
 */
@Configuration
public class GatewayRoutesConfig {

    /** Storefront endpoints that are reachable without a token, hence rate-limited. */
    private static final int STOREFRONT_REPLENISH_RATE = 20;
    private static final int STOREFRONT_BURST_CAPACITY = 40;

    /** The 13 services whose OpenAPI spec the gateway aggregates. */
    private static final List<String> DOCS_SERVICES = List.of(
            "catalog", "inventory", "customer", "cart", "payment", "order",
            "promotion", "shipping", "checkout", "notification", "review", "search",
            "procurement");

    private final RedisRateLimiter redisRateLimiter;
    private final KeyResolver ipKeyResolver;

    /**
     * @param redisRateLimiter Gateway's auto-configured limiter. Injected rather
     *     than replaced by a locally-declared {@code RedisRateLimiter} bean on
     *     purpose: {@code RequestRateLimiterGatewayFilterFactory} resolves a
     *     default {@code RateLimiter} by type, so a second bean of this type
     *     makes that injection ambiguous and fails the context at startup.
     * @param ipKeyResolver the per-client-IP bucket key, from {@link RateLimitConfig}.
     */
    public GatewayRoutesConfig(RedisRateLimiter redisRateLimiter, KeyResolver ipKeyResolver) {
        this.redisRateLimiter = redisRateLimiter;
        this.ipKeyResolver = ipKeyResolver;
    }

    @Bean
    public RouteLocator oriahRouteLocator(RouteLocatorBuilder builder) {
        // Per-route limiter config, keyed by route id. This is exactly the
        // mechanism the YAML form used: RedisRateLimiter.loadConfiguration(routeId)
        // looks the id up in this map and falls back to its default config. We
        // populate it here rather than declaring our own limiter bean, for the
        // ambiguity reason in the constructor javadoc.
        registerStorefrontRateLimit("catalog-public-route");
        registerStorefrontRateLimit("search-route");
        registerStorefrontRateLimit("catalog-taxonomy-route");
        registerStorefrontRateLimit("catalog-variants-route");
        // Images get their OWN, far more generous limiter rather than the
        // storefront one. A grid of 24 product cards is 24 or more image
        // requests in a single paint, against a storefront allowance of 20
        // per second with a burst of 40 -- so the shop would throttle itself
        // on first load.
        //
        // Generous, but NOT absent: this path serves bytes anonymously, which
        // makes it the cheapest bandwidth-amplification target in the stack.
        registerImageRateLimit("catalog-images-route");

        RouteLocatorBuilder.Builder routes = builder.routes();

        // ---------------------------------------------------------------
        // Nested product reviews. MUST stay ahead of catalog-public-route;
        // see note 1 in the class javadoc. Two patterns in one predicate:
        // the bare collection path and everything beneath it (…/aggregate).
        // ---------------------------------------------------------------
        routes = routes.route("product-reviews-route", r -> r
                .order(-10)
                .path("/api/v1/products/*/reviews", "/api/v1/products/*/reviews/**")
                .filters(f -> resilientStorefront(f, "product-reviews"))
                .uri("lb://review"));

        // ------------------------- storefront -------------------------
        // Anonymous, so rate-limited: a runaway client cannot be stopped by
        // revoking a token it never had.
        routes = routes.route("catalog-public-route", r -> r
                .path("/api/v1/products/**")
                .filters(f -> resilientStorefront(f, "catalog")
                        .requestRateLimiter(c -> c
                                .setRateLimiter(redisRateLimiter)
                                .setKeyResolver(ipKeyResolver)))
                .uri("lb://catalog"));

        // ------------------------- product images -------------------------
        // Image BYTES, on a deliberately separate top-level path rather than
        // nested under /api/v1/products/**.
        //
        // Nesting it there would have inherited two filters that are actively
        // harmful for binary responses:
        //
        //   The rate limiter (20/s, burst 40) would throttle a single page
        //   paint, because one product grid is dozens of image requests.
        //
        //   The circuit breaker would, when open, hand an <img> tag the
        //   /contactSupport fallback -- an RFC 7807 application/problem+json
        //   document with HTTP 503, where image data was expected. A broken
        //   image is a better failure than a JSON document pretending to be
        //   a photograph.
        //
        //   And storefrontRetry retries GETs on 5xx twice, which during a
        //   wobble triples the bytes crossing the edge at exactly the moment
        //   the service is struggling.
        //
        // So: no breaker, no retry, and a limiter sized for a page of images.
        // order(-20) keeps it ahead of everything else and is the FIRST call
        // in the lambda, which Spring Cloud Gateway's DSL requires (see note
        // 2 in the class javadoc).
        routes = routes.route("catalog-images-route", r -> r
                .order(-20)
                .path("/api/v1/product-images/**")
                .filters(f -> f.requestRateLimiter(c -> c
                        .setRateLimiter(redisRateLimiter)
                        .setKeyResolver(ipKeyResolver)))
                .uri("lb://catalog"));

        // ------------------- storefront taxonomy -------------------
        // The category tree and the product-type definitions. JSON reads, so
        // they get the same treatment as the other anonymous storefront
        // routes -- including the circuit breaker, because a problem+json
        // fallback is a perfectly reasonable answer for a failed JSON read.
        // (That is NOT true of the image route added later, where an <img>
        // tag receiving an RFC 7807 body is useless.)
        routes = routes.route("catalog-taxonomy-route", r -> r
                .path("/api/v1/categories/**", "/api/v1/product-types/**")
                .filters(f -> resilientStorefront(f, "catalog-taxonomy")
                        .requestRateLimiter(c -> c
                                .setRateLimiter(redisRateLimiter)
                                .setKeyResolver(ipKeyResolver)))
                .uri("lb://catalog"));

        // Variant pricing. Routed at the edge rather than kept internal
        // because the storefront bag calls it directly to re-check its lines,
        // not only cart-service. Rate limited like its neighbours: it is
        // anonymous, and an anonymous caller cannot be stopped by revoking a
        // token it never had.
        routes = routes.route("catalog-variants-route", r -> r
                .path("/api/v1/variants/**")
                .filters(f -> resilientStorefront(f, "catalog-variants")
                        .requestRateLimiter(c -> c
                                .setRateLimiter(redisRateLimiter)
                                .setKeyResolver(ipKeyResolver)))
                .uri("lb://catalog"));

        routes = routes.route("search-route", r -> r
                .path("/api/v1/search/**")
                .filters(f -> resilientStorefront(f, "search")
                        .requestRateLimiter(c -> c
                                .setRateLimiter(redisRateLimiter)
                                .setKeyResolver(ipKeyResolver)))
                .uri("lb://search"));

        // ------------------------ authenticated ------------------------
        routes = simple(routes, "customer-route", "/api/v1/customers/**", "customer");
        routes = simple(routes, "cart-route", "/api/v1/carts/**", "cart");
        routes = simple(routes, "payment-route", "/api/v1/payments/**", "payment");
        routes = simple(routes, "order-route", "/api/v1/orders/**", "order");
        routes = simple(routes, "promotion-route", "/api/v1/promotions/**", "promotion");
        routes = simple(routes, "shipping-route", "/api/v1/shipping/**", "shipping");
        routes = simple(routes, "checkout-route", "/api/v1/checkout/**", "checkout");
        routes = simple(routes, "notification-route", "/api/v1/notifications/**", "notification");
        routes = simple(routes, "review-route", "/api/v1/reviews/**", "review");

        // --------------------------- admin ----------------------------
        // Note the role guarding these is NOT derivable from the path:
        // /api/v1/admin/promotions/** is guarded by CATALOG_ADMIN, not a
        // PROMOTION_ADMIN role (no such role exists in the realm). The
        // authoritative mapping lives in each service's @PreAuthorize.
        routes = simple(routes, "catalog-admin-route", "/api/v1/admin/products/**", "catalog");
        routes = simple(routes, "catalog-categories-admin-route", "/api/v1/admin/categories/**", "catalog");
        routes = simple(routes, "inventory-admin-route", "/api/v1/admin/inventory/**", "inventory");
        // Cost and margin reads: the lots and the sale rows of the stock ledger. A prefix of their own, so
        // BOOKKEEPER (COST_VIEWER, but not INVENTORY_ADMIN) can read cost and SALES cannot; the matcher in
        // SecurityConfig has guarded it since Phase 0, and inventory's controller checks COST_VIEWER again.
        routes = simple(routes, "inventory-costs-admin-route", "/api/v1/admin/costs/**", "inventory");
        // Pricing (Phase 6): setting a price against what the stock cost, and seeing where every price stands. A prefix of
        // its own because /api/v1/admin/products/** is CATALOG_ADMIN alone and the pricing screen shows landed cost: the
        // matcher in SecurityConfig needs CATALOG_ADMIN and COST_VIEWER both, and catalog's controller checks both again.
        routes = simple(routes, "catalog-pricing-admin-route", "/api/v1/admin/pricing/**", "catalog");
        routes = simple(routes, "customer-admin-route", "/api/v1/admin/customers/**", "customer");
        routes = simple(routes, "order-admin-route", "/api/v1/admin/orders/**", "order");
        routes = simple(routes, "promotion-admin-route", "/api/v1/admin/promotions/**", "promotion");
        routes = simple(routes, "payment-admin-route", "/api/v1/admin/payments/**", "payment");
        // Everything under /api/v1/admin/procurement/ is procurement's: suppliers
        // now, consignments and purchase orders in later phases, each guarded by
        // PROCUREMENT_ADMIN at the edge (SecurityConfig) and again in the service.
        routes = simple(routes, "procurement-admin-route", "/api/v1/admin/procurement/**", "procurement");

        // --------------------- OpenAPI aggregation ---------------------
        // /v3/api-docs/<svc> on the edge -> /v3/api-docs upstream. These back
        // the swagger-ui dropdown (springdoc.swagger-ui.urls in
        // application.yaml); the two lists must stay in lockstep, and these
        // paths must remain reachable without a token or the dropdown breaks.
        for (String service : DOCS_SERVICES) {
            routes = routes.route(service + "-docs", r -> r
                    .path("/v3/api-docs/" + service)
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri("lb://" + service));
        }

        return routes.build();
    }

    /**
     * Circuit breaker plus retry, applied to the anonymous storefront GET
     * routes and only to those.
     *
     * <p>Scoped deliberately rather than applied globally. These three paths
     * are what an anonymous visitor hits first, they are all idempotent reads,
     * and a degraded answer beats a hang. The authenticated routes get neither:
     * a breaker on {@code /api/v1/checkout/**} would convert a payment failure
     * into a cheerful "try again shortly" while the caller has no way to know
     * whether the charge went through, and retry on a write can place two
     * orders.
     *
     * <p>The course's coverage is uneven in a way its own material calls a
     * teaching artefact: of its three routes, one has a breaker, a different
     * one has retry, and a third has the rate limiter — so only one route can
     * ever reach the fallback, and failures on the other two surface raw. Here
     * the three storefront routes get the same treatment, and the reasoning for
     * excluding the rest is written down.
     *
     * <p>Each breaker gets its own name, so one failing service opens only its
     * own breaker. A shared name would make a catalog outage also cut off
     * search.
     */
    private static org.springframework.cloud.gateway.route.builder.GatewayFilterSpec resilientStorefront(
            org.springframework.cloud.gateway.route.builder.GatewayFilterSpec filters, String breakerName) {
        return filters
                .circuitBreaker(c -> c
                        .setName(breakerName + "-cb")
                        .setFallbackUri("forward:" + FallbackController.FALLBACK_PATH))
                .retry(ResilienceConfig::storefrontRetry);
    }

    /**
     * A route that is nothing but "this path prefix goes to that service":
     * no filters, no ordering, default order 0. Most of the API routes
     * are this shape, and writing them out longhand would bury the three that
     * are not.
     */
    private static RouteLocatorBuilder.Builder simple(
            RouteLocatorBuilder.Builder routes, String id, String pathPattern, String service) {
        return routes.route(id, r -> r.path(pathPattern).uri("lb://" + service));
    }

    /**
     * The image route's allowance.
     *
     * <p>Ten times the storefront's, sized for a product grid: a page of 24
     * cards with two images each is ~48 requests in one paint, and a visitor
     * scrolling a category hits several pages in quick succession. The burst
     * matters more than the sustained rate here, because image requests arrive
     * all at once rather than spread out.
     */
    private static final int IMAGE_REPLENISH_RATE = 200;
    private static final int IMAGE_BURST_CAPACITY = 400;

    private void registerImageRateLimit(String routeId) {
        redisRateLimiter.getConfig().put(routeId, new RedisRateLimiter.Config()
                .setReplenishRate(IMAGE_REPLENISH_RATE)
                .setBurstCapacity(IMAGE_BURST_CAPACITY));
    }

    private void registerStorefrontRateLimit(String routeId) {
        redisRateLimiter.getConfig().put(routeId, new RedisRateLimiter.Config()
                .setReplenishRate(STOREFRONT_REPLENISH_RATE)
                .setBurstCapacity(STOREFRONT_BURST_CAPACITY));
    }

    /**
     * Unused, but kept as the compile-time reminder of the two spec types the
     * DSL hands back: {@link PredicateSpec} (where {@code order} and the
     * predicates live) and {@link Buildable}&lt;{@link Route}&gt; (what
     * {@code uri(...)} returns and the lambda must yield).
     */
    @SuppressWarnings("unused")
    private static Buildable<Route> shapeReminder(PredicateSpec spec) {
        return spec.path("/never-matched").uri("lb://nowhere");
    }
}
