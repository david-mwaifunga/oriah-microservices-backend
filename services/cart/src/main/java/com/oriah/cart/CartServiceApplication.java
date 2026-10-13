package com.oriah.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
// For com.oriah.cart.client.CatalogPricingClient. Without this the interface is
// just an interface: the context starts perfectly happily and the bean simply
// does not exist, which is why CatalogPricingClientWiringTest asserts on the
// bean rather than on the context starting.
@EnableFeignClients
// Registers com.oriah.cart.dto.CartContactInfoDto. Matches catalog's
// precedent rather than the course's
// @EnableConfigurationProperties(X.class), which has to be re-edited for
// every future properties class.
@ConfigurationPropertiesScan
@Slf4j
public class CartServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
        log.info("cart-service ready");
    }
}
