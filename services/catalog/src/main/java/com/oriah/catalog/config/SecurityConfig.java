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
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
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
