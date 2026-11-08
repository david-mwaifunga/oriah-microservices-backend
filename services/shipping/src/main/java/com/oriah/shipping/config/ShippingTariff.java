package com.oriah.shipping.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What shipping quotes in. Until Phase 7 that is all its configuration is:
 * the only option is self-collect at no charge (MVP_PLAN Phase 0, item 1),
 * and Phase 7 replaces the old zone rate card with delivery prices per town,
 * which admin manages and publishes -- data, not configuration.
 *
 * <p>{@code @Validated}, without which the constraints below are INERT:
 * Spring Boot runs the validator over a {@code @ConfigurationProperties} type
 * only when it carries this annotation, and a service that quotes from a
 * broken configuration is worse than one that does not start.
 */
// ignoreUnknownFields = false: a leftover zone card (a stale override in
// config-repo, say) refuses to start the service instead of being silently
// ignored while looking configured. Pinned by TariffBindingTest.
@ConfigurationProperties(prefix = "oriah.shipping.tariff", ignoreUnknownFields = false)
@Validated
public record ShippingTariff(
        @NotNull @Size(min = 3, max = 3) String currency
) {
}