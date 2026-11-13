package com.oriah.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
// The clients order calls: customer (whose address), catalog (the prices), shipping (the delivery price) and inventory (the hold on the pieces).
// Order never calls payment: money reaches it as an event (a staff-confirmed payment), and it answers by event.
@EnableFeignClients
// Registers com.oriah.order.dto.OrderContactInfoDto. Matches catalog's
// precedent rather than the course's
// @EnableConfigurationProperties(X.class), which has to be re-edited for
// every future properties class.
@ConfigurationPropertiesScan
@Slf4j
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
        log.info("order-service ready");
    }
}
