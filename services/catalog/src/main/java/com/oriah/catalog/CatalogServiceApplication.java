package com.oriah.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for the catalog microservice.
 *
 * <p>{@code @SpringBootApplication} combines {@code @Configuration},
 * {@code @EnableAutoConfiguration} and {@code @ComponentScan} with this
 * class's package as the scan root, so every {@code @Service},
 * {@code @RestController} and {@code @Repository} under
 * {@code com.oriah.catalog.*} is auto-registered.
 *
 * <p>The application can be run two ways during development:
 * <ol>
 *   <li>{@code mvn -pl services/catalog spring-boot:run} —
 *       needs a Postgres reachable via {@code spring.datasource.*}
 *       (typically supplied via env vars).</li>
 *   <li>{@code mvn -pl services/catalog spring-boot:test-run} —
 *       picks up {@code TestCatalogServiceApplication} which
 *       starts a Testcontainers-managed Postgres alongside the app.
 *       This is the easy path on a developer laptop.</li>
 * </ol>
 */
@SpringBootApplication
@Slf4j
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
        // Banner-style ready line so a tailing developer immediately
        // sees "yes, the service is up." Spring already logs its own
        // "Started" line right before this, but ours is parseable on
        // a single grep ("catalog-service ready") if needed.
        log.info("catalog-service ready");
    }
}
