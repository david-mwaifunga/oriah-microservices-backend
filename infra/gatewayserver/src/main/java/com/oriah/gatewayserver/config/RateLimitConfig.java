package com.oriah.gatewayserver.config;

import java.util.Objects;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

/**
 * Beans referenced by the {@code RequestRateLimiter} filter declared
 * in {@code application.yaml} on the anonymous public routes.
 *
 * <p>{@link #ipKeyResolver()} returns the remote address as the
 * bucket key, so one rogue client cannot starve the bucket for the
 * whole world. In production behind a load balancer or CDN, we would
 * extract from the {@code X-Forwarded-For} header instead -- noted
 * but not implemented because the local dev stack hits the gateway
 * directly.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                Objects.requireNonNull(exchange.getRequest().getRemoteAddress(),
                        "remote address required for IP rate-limiter")
                        .getAddress().getHostAddress());
    }
}
