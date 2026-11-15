package com.oriah.finance.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Every service validates tokens itself, on top of the gateway: services call each other directly (bypassing the gateway) and the ports are only closed in
 * production, so the gateway's rule for this prefix is a layer, not the only one.
 *
 * <p>Everything under {@code /api/v1/admin/finance/**} needs {@code FINANCE_ADMIN}: the expenses AND the profit reports (CLIENT_DECISIONS: "Expenses and the profit
 * reports"), held by the owner and the bookkeeper and by no one else (the manager reads cost, not the books). The gateway enforces it for the prefix; here the rule is
 * stated on every controller with {@code @PreAuthorize}, so a refusal is answered in the platform's own problem+json (a refusal by the filter chain would have no body),
 * and {@code HandlerRolesTest} walks every handler and fails if one lacks it.
 *
 * <p>Copied per service on purpose: there is no shared security module, and the Keycloak role mapping below is the same in all of them. Keycloak puts realm roles in
 * {@code realm_access.roles}; Spring wants authorities named {@code ROLE_<NAME>}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(SecurityConfig::rolesFromKeycloak);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> rolesFromKeycloak(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return List.of();
        Object rolesObj = realmAccess.get("roles");
        if (!(rolesObj instanceof List<?> rolesList)) return List.of();
        return ((List<String>) rolesList).stream()
                .map(r -> "ROLE_" + r.toUpperCase())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableList());
    }
}
