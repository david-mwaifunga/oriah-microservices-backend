package com.oriah.catalog.client;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import feign.Feign;

/**
 * Configuration of {@link InventoryCostClient} alone. Deliberately NOT annotated {@code @Configuration}: Spring Cloud OpenFeign
 * applies it to this client's own context, and a {@code @Configuration} class would be picked up by component scanning and
 * applied to every Feign client in the service.
 *
 * <p>A plain, prototype-scoped {@link Feign.Builder} is how OpenFeign is told NOT to wrap this one client in the circuit breaker
 * ({@code spring.cloud.openfeign.circuitbreaker.enabled} stays true for the stock badge). The breaker dispatches every call to a
 * pool thread, and this client's token is the caller's, handed in explicitly; still, a time limiter on that thread would also
 * cut a slow cost read short without a way to tell the person why. The timeouts are plain Feign ones, in application.yaml under
 * {@code spring.cloud.openfeign.client.config.inventory-costs}, and nothing is retried.
 */
class InventoryCostClientConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    Feign.Builder inventoryCostFeignBuilder() {
        return Feign.builder();
    }
}
