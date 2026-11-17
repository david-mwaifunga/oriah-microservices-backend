package com.oriah.payment.config;

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
                        // ZynlePay's callback (Phase 13): ZynlePay sends no token. This one path, POST only. The message is trusted with nothing:
                        // see ZynlePayCallbackController.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, com.oriah.payment.controller.ZynlePayCallbackController.PATH).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.bearerTokenResolver(SecurityConfig::bearerToken)
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    private static final org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver BEARER =
            new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();

    /**
     * The caller's token, as everywhere, except on ZynlePay's callback: whatever Authorization header a message there carries is not a token of
     * ours and must not be judged as one (a bad one would turn ZynlePay's message into a 401 before the open door is reached).
     */
    private static String bearerToken(jakarta.servlet.http.HttpServletRequest request) {
        if ("POST".equals(request.getMethod()) && com.oriah.payment.controller.ZynlePayCallbackController.PATH.equals(request.getRequestURI())) {
            return null;
        }
        return BEARER.resolve(request);
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
