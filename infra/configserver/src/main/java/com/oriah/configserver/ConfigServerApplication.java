package com.oriah.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring Cloud Config Server.
 *
 * <p>{@code @EnableConfigServer} wires the HTTP endpoints
 * {@code /{application}/{profile}[/{label}]} that clients hit on startup
 * to fetch their externalized configuration.
 *
 * <p>For this iteration the backend is {@code native} (filesystem),
 * pointing at {@code classpath:/config/}. We swap to a Git backend in a
 * follow-up cycle once the basic plumbing is understood.
 *
 * <p>Default port is 8888 — the Spring Cloud Config client default search
 * URL, so clients that don't override {@code spring.cloud.config.uri}
 * find this server automatically when running locally.
 */
@SpringBootApplication
@EnableConfigServer
@Slf4j
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
        log.info("configserver ready");
    }
}
