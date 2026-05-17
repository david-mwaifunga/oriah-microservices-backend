package com.oriah.eurekaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

import lombok.extern.slf4j.Slf4j;

/**
 * Netflix Eureka discovery server.
 *
 * <p>{@code @EnableEurekaServer} wires the Eureka registry endpoints:
 * <ul>
 *   <li>{@code GET    /eureka/apps}          full registry snapshot</li>
 *   <li>{@code GET    /eureka/apps/{app}}    instances of one service</li>
 *   <li>{@code POST   /eureka/apps/{app}}    register a new instance</li>
 *   <li>{@code PUT    /eureka/apps/{app}/{id}}  heartbeat (lease renewal)</li>
 *   <li>{@code DELETE /eureka/apps/{app}/{id}}  cancel a registration</li>
 * </ul>
 * Plus the human-facing dashboard at {@code GET /}.
 *
 * <p>Default port is 8761 — the Eureka client default. Services that
 * don't override {@code eureka.client.service-url.defaultZone} find this
 * server automatically when running locally.
 *
 * <p>This is a <b>standalone</b> Eureka — the application.yaml sets
 * {@code register-with-eureka: false} + {@code fetch-registry: false}.
 * If we ever scale to a Eureka cluster (highly available registry),
 * each peer's config would point at the other peer(s) instead.
 */
@SpringBootApplication
@EnableEurekaServer
@Slf4j
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
        log.info("eurekaserver ready");
    }
}
