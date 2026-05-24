package com.oriah.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.oriah.jpa.KeycloakAuditorAware;

/**
 * Activates Spring Data JPA auditing for the search service.
 * See services/catalog/.../config/JpaConfig.java for the pattern
 * (per-service @Configuration rather than starter @AutoConfiguration
 * so @WebMvcTest slices do not bootstrap JPA they do not need).
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditor")
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditor() {
        return new KeycloakAuditorAware();
    }
}
