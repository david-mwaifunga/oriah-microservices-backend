package com.oriah.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.oriah.jpa.AuditAwareImpl;

/**
 * Activates Spring Data JPA auditing for the catalog service.
 *
 * <p>{@code @EnableJpaAuditing(auditorAwareRef = "auditAwareImpl")} wires the
 * {@code jpaAuditingHandler} that the {@code AuditingEntityListener}
 * on {@link com.oriah.jpa.BaseEntity} delegates to on every
 * {@code persist}/{@code update}. The handler reads the
 * {@code auditor} bean below to populate {@code created_by} and
 * {@code updated_by}; {@code @CreatedDate} / {@code @LastModifiedDate}
 * are filled from the system clock automatically.
 *
 * <p>This config lives in the catalog module (rather than as an
 * auto-configuration in {@code oriah-jpa-base}) on purpose: a plain
 * {@code @Configuration} class gets picked up by
 * {@code @SpringBootTest} but filtered out by {@code @WebMvcTest},
 * so the WebMvc slice tests do not try to bootstrap JPA. See
 * docs/PROGRESS.md "Why T1.6 was harder than expected" for the
 * post-mortem.
 *
 * <p>{@code @DataJpaTest} slice tests can opt in via
 * {@code @Import(JpaConfig.class)} if they need audit fields
 * populated; otherwise the listener no-ops and the columns stay null,
 * which is fine because the V2 Flyway migration makes them nullable.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditAwareImpl")
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditAwareImpl() {
        return new AuditAwareImpl();
    }
}
