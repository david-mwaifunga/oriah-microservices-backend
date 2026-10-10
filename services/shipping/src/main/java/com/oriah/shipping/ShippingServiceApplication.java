package com.oriah.shipping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
// Registers com.oriah.shipping.dto.ShippingContactInfoDto. Matches catalog's
// precedent rather than the course's @EnableConfigurationProperties(X.class),
// which has to be re-edited for every future properties class and would leave
// 11 app classes shaped differently from catalog's.
@ConfigurationPropertiesScan
@Slf4j
public class ShippingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShippingServiceApplication.class, args);
        log.info("shipping-service ready");
    }
}
