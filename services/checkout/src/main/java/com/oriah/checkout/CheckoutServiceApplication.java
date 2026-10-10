package com.oriah.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
// Registers com.oriah.checkout.dto.CheckoutContactInfoDto. Matches catalog's
// precedent rather than the course's
// @EnableConfigurationProperties(X.class), which has to be re-edited for
// every future properties class.
@ConfigurationPropertiesScan
@EnableFeignClients
@Slf4j
public class CheckoutServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CheckoutServiceApplication.class, args);
        log.info("checkout-service ready");
    }
}
