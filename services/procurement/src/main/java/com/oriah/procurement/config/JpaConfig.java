package com.oriah.procurement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.oriah.jpa.AuditAwareImpl;

/**
 * Activates Spring Data JPA auditing for the procurement service, so every
 * supplier records who created and last changed it (the Keycloak subject, or
 * "system" outside a request). See services/catalog/.../config/JpaConfig.java for
 * the pattern (per-service @Configuration rather than starter @AutoConfiguration
 * so web slices do not bootstrap JPA they do not need).
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditAwareImpl")
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditAwareImpl() {
        return new AuditAwareImpl();
    }
}
