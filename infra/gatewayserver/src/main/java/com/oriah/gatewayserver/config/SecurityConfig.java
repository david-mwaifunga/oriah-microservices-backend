package com.oriah.gatewayserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * JWT validation at the edge, in ADDITION to the 12 services' own resource
 * servers.
 *
 * <p>Not instead of: every service is directly reachable on the compose network
 * (and on a host port), so gateway-only auth would be a downgrade dressed up as
 * a convention. The services remain the authority on their own role checks;
 * this chain's job is to reject unauthenticated traffic before it fans out to
 * twelve applications, and to enforce the admin roles at one place.
 *
 * <h2>Rules mirror the services, and were derived by reading them</h2>
 *
 * <p>A sweep of all 12 services produced two findings that this config would
 * otherwise have got wrong:
 *
 * <ul>
 *   <li>For 8 of the 12, the real rules live in {@code @PreAuthorize} on
 *       controllers rather than in {@code SecurityConfig}. Reading only the
 *       SecurityConfig files would have produced a gateway looser than reality
 *       by one role check per service.</li>
 *   <li>{@code /api/v1/admin/promotions/**} is guarded by
 *       {@code CATALOG_ADMIN}, not {@code PROMOTION_ADMIN} — no such role
 *       exists in the realm. A convention mapping
 *       {@code /admin/<svc>/** → <SVC>_ADMIN} would be wrong there, and would
 *       invent a guard for payments, where {@code PAYMENT_ADMIN} exists in the
 *       realm but guards nothing.</li>
 * </ul>
 *
 * <p>Non-admin API paths require only a valid token here, not
 * {@code hasRole("USER")}. Enforcing USER at the edge as well would make the
 * gateway stricter than some service, which is the failure mode that breaks a
 * working call; the services already enforce it.
 *
 * <h2>Two deliberate departures from the course reference</h2>
 *
 * <p><strong>No blanket GET permit.</strong> The course's first rule is
 * {@code .pathMatchers(HttpMethod.GET).permitAll()} — a method-only matcher
 * with no path. Matchers are evaluated in order and first match wins, so that
 * single line permits EVERY GET through its gateway, and its
 * {@code hasRole(...)} checks only ever apply to non-GET methods. Here every
 * permit is path-scoped.
 *
 * <p><strong>A terminal {@code anyExchange} rule.</strong> Spring Security
 * WebFlux grants access when no matcher matches, so a config without one
 * leaves every unlisted path open — including the fallback endpoint for
 * non-GET methods and, where exposed, actuator. This chain ends in
 * {@code anyExchange().authenticated()}, so the default is deny.
 *
 * <h2>Spring Security 7 API</h2>
 *
 * <p>The course's chained style does not compile here. All eight no-arg entry
 * points on {@link ServerHttpSecurity} ({@code csrf()}, {@code cors()},
 * {@code authorizeExchange()}, {@code oauth2ResourceServer()}, …) and all 17
 * {@code and()} methods were removed in Security 7; only the
 * {@code Customizer} overloads remain. So {@code .csrf().disable()} becomes
 * {@code .csrf(CsrfSpec::disable)} and
 * {@code .oauth2ResourceServer().jwt().and()} becomes
 * {@code .oauth2ResourceServer(o -> o.jwt(j -> ...))}. Loud compile errors
 * rather than silent ones — the real risk is budgeting it as a small edit.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Storefront reads that must work with no token at all. */
    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/products/**",
            "/api/v1/search/**"
    };

    /**
     * Probe, metrics and documentation paths.
     *
     * <p>{@code /actuator/**} is permitted wholesale because <em>exposure</em>
     * is the gate that matters here, and it is curated in
     * {@code application.yaml} to {@code health,info,metrics,prometheus} —
     * everything exposed is something the monitoring stack reads without
     * credentials. Prometheus in particular scrapes
     * {@code gatewayserver:8080/actuator/prometheus} tokenless, so requiring
     * auth would stop monitoring silently rather than fail loudly. The
     * sensitive endpoints are unreachable because they are not exposed, which
     * both {@code GatewayServerApplicationTests} and {@code smoke-test.ps1}
     * step 18 assert.
     *
     * <p>The api-docs and swagger paths back the gateway's own aggregated
     * Swagger UI, which fetches each service's spec through
     * {@code /v3/api-docs/<svc>}; a token requirement there breaks its
     * dropdown.
     */
    private static final String[] PUBLIC_INFRASTRUCTURE_PATHS = {
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            // The circuit-breaker fallback. Reachable without a token by
            // necessity: it is what an anonymous storefront request gets when
            // catalog is down, and a 401 there would replace a useful message
            // with a misleading one.
            "/contactSupport"
    };

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                // Stateless bearer-token API: there is no session or cookie for
                // an attacker to ride, and CSRF tokens would simply break every
                // non-GET call.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // Preflight first. A browser sends OPTIONS with no
                        // Authorization header; rejecting it means the real
                        // request is never sent and the browser reports an
                        // opaque CORS error instead of a 401.
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()

                        .pathMatchers(PUBLIC_INFRASTRUCTURE_PATHS).permitAll()

                        // Method-scoped on purpose. review is the only service
                        // where one path has different auth per method: GET
                        // /api/v1/products/{id}/reviews is public, POST to the
                        // identical path needs USER. An unscoped permit would
                        // make posting a review anonymous.
                        .pathMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()

                        // Admin roles, each one the role the owning service
                        // actually checks. hasRole adds the ROLE_ prefix
                        // itself, and KeycloakRoleConverter produces prefixed
                        // authorities, so these must NOT be spelled ROLE_X
                        // here. Mixing hasRole and hasAuthority conventions is
                        // the easiest way to 403 every authenticated call.
                        .pathMatchers("/api/v1/admin/products/**").hasRole("CATALOG_ADMIN")
                        .pathMatchers("/api/v1/admin/inventory/**").hasRole("INVENTORY_ADMIN")
                        .pathMatchers("/api/v1/admin/customers/**").hasRole("CUSTOMER_ADMIN")
                        .pathMatchers("/api/v1/admin/orders/**").hasRole("ORDER_ADMIN")
                        .pathMatchers("/api/v1/admin/promotions/**").hasRole("CATALOG_ADMIN")

                        // Deny by default.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveKeycloakConverter())))
                .build();
    }

    /**
     * Bridges our {@code Converter<Jwt, Collection<GrantedAuthority>>} onto the
     * reactive chain.
     *
     * <p>The two granted-authorities setters are easy to confuse: the
     * imperative {@link JwtAuthenticationConverter} takes
     * {@code Converter<Jwt, Collection<GrantedAuthority>>}, while
     * {@code ReactiveJwtAuthenticationConverter} takes
     * {@code Converter<Jwt, Flux<GrantedAuthority>>}. The Collection form
     * reaches the reactive chain only through
     * {@link ReactiveJwtAuthenticationConverterAdapter}; handing it straight to
     * the reactive converter does not compile.
     */
    private ReactiveJwtAuthenticationConverterAdapter reactiveKeycloakConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }
}
