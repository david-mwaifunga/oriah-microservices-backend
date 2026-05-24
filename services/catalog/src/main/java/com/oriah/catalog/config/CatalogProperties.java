package com.oriah.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Bound from the {@code oriah.catalog.*} config tree -- specifically
 * {@code oriah.catalog.low-stock-threshold} served by the Spring Cloud
 * Config Server out of {@code ../config-repo/catalog.yaml}.
 *
 * <p>Mutable class (not a record) on purpose: Spring Cloud Context's
 * {@code ConfigurationPropertiesRebinder} re-binds existing
 * {@code @ConfigurationProperties} beans IN PLACE on {@code /actuator/
 * refresh}, so consumers that already hold a reference see the new
 * values without being themselves {@code @RefreshScope}-wrapped.
 * Records cannot be rebound in place because they are immutable;
 * Spring would have to create a new record instance and existing
 * consumers would keep reading the old one.
 *
 * <p>Wired automatically because catalog has
 * {@code @ConfigurationPropertiesScan} on its application class
 * (added when we introduced this bean -- see CatalogServiceApplication).
 *
 * <p>Demo flow proving the Spring Cloud Bus loop:
 * <ol>
 *   <li>Edit {@code config-repo/catalog.yaml}:
 *       {@code oriah.catalog.low-stock-threshold: 2}.</li>
 *   <li>{@code git commit + git push} (or just commit on local
 *       file:// remote).</li>
 *   <li>{@code POST http://localhost:8888/actuator/busrefresh}.</li>
 *   <li>configserver fans out a RefreshRemoteApplicationEvent on the
 *       {@code springCloudBus} Kafka topic.</li>
 *   <li>Every {@code spring-cloud-starter-bus-kafka} client (catalog
 *       included) consumes the event and runs its own
 *       {@code /actuator/refresh} internally.</li>
 *   <li>{@code ProductService}'s injected {@code CatalogProperties}
 *       reference is the same object; its {@code lowStockThreshold}
 *       field flips to 2 in place; the next request sees the new
 *       behavior. No restart.</li>
 * </ol>
 */
@ConfigurationProperties(prefix = "oriah.catalog")
@Validated
public class CatalogProperties {

    /**
     * Cut-off below which {@code Availability} flips from IN_STOCK to
     * LOW_STOCK ("Only N left"). Lives in config-repo so we can dial
     * it per-environment without redeploying catalog.
     */
    @NotNull
    @Min(1)
    private Integer lowStockThreshold = 5;

    public Integer getLowStockThreshold() {
        return lowStockThreshold;
    }

    public void setLowStockThreshold(Integer lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }
}
