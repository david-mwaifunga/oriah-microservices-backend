package com.oriah.payment.config;

import java.time.Clock;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.oriah.jpa.AuditAwareImpl;

/**
 * Activates Spring Data JPA auditing for the payment service.
 * See services/catalog/.../config/JpaConfig.java for the pattern
 * (per-service @Configuration rather than starter @AutoConfiguration
 * so @WebMvcTest slices do not bootstrap JPA they do not need).
 *
 * <p>The audit times (created_at, updated_at) come from payment's own
 * {@link Clock}, not the JVM's. The reconciler decides how long an intent has
 * been CHARGING from its updated_at, measured against that same clock; with
 * two clocks, a charge made "three days ago" in a test would still look a
 * moment old, and nothing could prove what a late reconciliation records.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditAwareImpl", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditAwareImpl() {
        return new AuditAwareImpl();
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
