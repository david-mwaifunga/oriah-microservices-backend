package com.oriah.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import lombok.extern.slf4j.Slf4j;

// The Feign client to order (OrderClient) belongs to the card path and is enabled by CardPathConfiguration, only while
// oriah.payment.card.enabled is true.
@SpringBootApplication
// Registers com.oriah.payment.dto.PaymentContactInfoDto. Matches catalog's
// precedent rather than the course's
// @EnableConfigurationProperties(X.class), which has to be re-edited for
// every future properties class.
@ConfigurationPropertiesScan
@Slf4j
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
        log.info("payment-service ready");
    }
}
