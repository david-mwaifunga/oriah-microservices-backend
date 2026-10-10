package com.oriah.jpa;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves the "who changed this row" auditor for Spring Data JPA's
 * {@code @CreatedBy} and {@code @LastModifiedBy} annotations.
 *
 * <p>Resolution rules, in order:
 * <ol>
 *   <li>If a JWT is in the {@code SecurityContext}, return the
 *       Keycloak {@code sub} claim -- the stable user id every
 *       Oriah service already keys its per-customer data on.</li>
 *   <li>Otherwise, if the {@code Authentication} is non-anonymous,
 *       return its {@code name} (defensive: should not happen in our
 *       stack today, but lets us swap auth types without losing
 *       audit data).</li>
 *   <li>If the {@code SecurityContext} is empty or anonymous --
 *       which is the case on Spring Cloud Stream Kafka consumer
 *       threads, where requests do not flow through the OAuth2
 *       resource-server filter chain -- return {@code "system"}.</li>
 * </ol>
 *
 * <p>Wired via {@code @Bean("auditor") AuditorAware<String> auditor()}
 * in each entity-bearing service's {@code JpaConfig}; the bean name
 * matches {@code @EnableJpaAuditing(auditorAwareRef = "auditor")} on
 * the same config class. Not a {@code @Component} here so this jar
 * stays a pure type library with no Spring context side effects.
 */
public class AuditAwareImpl implements AuditorAware<String> {

    static final String SYSTEM = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.of(SYSTEM);
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            String sub = jwt.getSubject();
            return Optional.of(sub != null ? sub : SYSTEM);
        }
        String name = auth.getName();
        return Optional.of(name != null ? name : SYSTEM);
    }
}
