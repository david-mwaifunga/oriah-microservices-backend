package com.oriah.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for the inventory microservice.
 *
 * <p>Tracks on-hand and reserved stock per product variant. Reservations
 * happen when a customer adds to cart; releases happen on cart abandon
 * or order failure; confirmation happens on shipment.
 *
 * <p>Same operational shape as catalog: Config Client, Eureka Client,
 * SpringDoc, Testcontainers in dev.
 */
@SpringBootApplication
// Registers com.oriah.inventory.dto.InventoryContactInfoDto. Matches catalog's
// precedent rather than the course's
// @EnableConfigurationProperties(X.class), which has to be re-edited for
// every future properties class.
@ConfigurationPropertiesScan
@Slf4j
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
        log.info("inventory-service ready");
    }
}
