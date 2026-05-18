package com.oriah.gatewayserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring Cloud Gateway — single front door for the Oriah API.
 *
 * <p>No {@code @EnableX} annotation is required: Spring Cloud Gateway
 * activates itself when {@code spring-cloud-starter-gateway} is on the
 * classpath. Route definitions live in {@code application.yaml} under
 * {@code spring.cloud.gateway.routes}.
 *
 * <p>This module is the only <b>reactive</b> (Netty + WebFlux) component
 * in the system. The data services (catalog, future inventory) stay on
 * the MVC stack — gateways generally do high-fanout I/O so reactive pays
 * off there; domain services usually don't.
 *
 * <p>Default port 8080 — the conventional "edge" port. Browsers and the
 * eventual React frontend hit this address; everything behind is
 * discovered via Eureka.
 */
@SpringBootApplication
@Slf4j
public class GatewayServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServerApplication.class, args);
        log.info("gatewayserver ready");
    }
}
