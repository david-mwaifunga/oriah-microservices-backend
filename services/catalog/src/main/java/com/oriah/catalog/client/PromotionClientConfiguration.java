package com.oriah.catalog.client;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import feign.Feign;

/**
 * Configuration of {@link PromotionClient} alone. Deliberately NOT annotated {@code @Configuration}: Spring Cloud OpenFeign applies it to
 * this client's own context, and a {@code @Configuration} class would be picked up by component scanning and applied to every Feign client in
 * the service.
 *
 * <p>A plain, prototype-scoped {@link Feign.Builder} is how OpenFeign is told NOT to wrap this one client in the circuit breaker
 * ({@code spring.cloud.openfeign.circuitbreaker.enabled} stays true for the stock badge). An open breaker would answer through a fallback,
 * and there must be no answer but the real one; its time limiter would also dispatch the call to a pool thread. The timeouts are plain Feign
 * ones, in application.yaml under {@code spring.cloud.openfeign.client.config.promotion-in-force} (half a second to connect, a second and a
 * half to read), and nothing is retried.
 */
class PromotionClientConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    Feign.Builder promotionFeignBuilder() {
        return Feign.builder();
    }
}
