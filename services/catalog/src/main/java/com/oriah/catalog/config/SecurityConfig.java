package com.oriah.catalog.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the catalog service.
 *
 * <p>Strategy: catalog is a <b>stateless</b> OAuth2 Resource Server.
 * Every request carries (or doesn't) a JWT signed by Keycloak in the
 * {@code Authorization: Bearer ...} header. There are no sessions and
 * no CSRF — clients re-present the token on each call.
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>{@code GET /api/v1/products/**}      — public, no auth.</li>
 *   <li>{@code /api/v1/admin/products/**}    — requires authenticated
 *       JWT; method-level {@code @PreAuthorize("hasRole('CATALOG_ADMIN')")}
 *       on {@link com.oriah.catalog.controller.AdminProductController}
 *       narrows further.</li>
 *   <li>{@code /actuator/health, /actuator/info} — public for orchestrator probes.</li>
 *   <li>{@code /swagger-ui.html, /v3/api-docs/**} — public so the API is browseable.</li>
 *   <li>Everything else — authenticated.</li>
 * </ul>
 *
 * <p>Role mapping: Keycloak puts realm roles in {@code realm_access.roles}
 * (a JSON array). Spring Security's default converter looks at the
 * {@code scope} claim instead, so {@link #grantedAuthoritiesFromKeycloak}
 * pulls roles from the Keycloak claim, prefixes them with {@code ROLE_}
 * so {@code hasRole(...)} works, and uppercases them so case differences
 * between realms don't break authorization.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Must precede the permitAll below: Spring Security
                        // matches in declaration order, first match wins. The
                        // contact-info response carries on-call phone numbers,
                        // which should not be readable anonymously from a
                        // public storefront edge -- unlike this service's
                        // product/stock/search reads, which are meant to be.
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/contact-info").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        // Added by the products phase. Each of these is public
                        // for the same reason the product reads are: a
                        // storefront visitor is anonymous.
                        //
                        // Listing them in the GATEWAY's PUBLIC_GET_PATHS is
                        // necessary but NOT sufficient -- this chain ends in
                        // anyRequest().authenticated(), so without these lines
                        // every one of them answers 401 when called, and the
                        // gateway's allow-list would simply be forwarding a
                        // request this service then refuses. PublicAccessTest
                        // calls this service DIRECTLY and anonymously to prove
                        // both halves are in place.
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/product-types/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/product-images/**").permitAll()
                        // POST, and permitAll, and both are deliberate. It is a
                        // read whose identifier list is too long for a URL, and
                        // prices are already public on the product page. The
                        // alternative -- requiring a token -- would mean
                        // cart-service relaying one, which it has no mechanism
                        // for, and would make the pricing path depend on auth
                        // while the product page it quotes does not.
                        .requestMatchers(HttpMethod.POST, "/api/v1/variants/pricing").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .build();
    }

    /**
     * Custom converter that pulls roles out of Keycloak's
     * {@code realm_access.roles} claim. Default Spring converter looks at
     * the {@code scope} claim, which Keycloak doesn't populate the way
     * Spring expects.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::grantedAuthoritiesFromKeycloak);
        return converter;
    }

    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> grantedAuthoritiesFromKeycloak(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Object rolesObj = realmAccess.get("roles");
        if (!(rolesObj instanceof List<?> rolesList)) {
            return List.of();
        }
        List<String> roles = (List<String>) rolesList;
        return roles.stream()
                .map(role -> "ROLE_" + role.toUpperCase())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableList());
    }
}
