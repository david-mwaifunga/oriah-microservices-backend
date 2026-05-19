package com.oriah.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@EnableFeignClients
@Slf4j
public class CheckoutServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CheckoutServiceApplication.class, args);
        log.info("checkout-service ready");
    }
}
