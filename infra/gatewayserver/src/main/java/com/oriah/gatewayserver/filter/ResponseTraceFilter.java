package com.oriah.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Echoes the correlation id back to the caller, so someone reporting a fault
 * can quote the id that ties their request to the server-side logs.
 *
 * <p><strong>Written with {@code beforeCommit}, and that is the whole point.</strong>
 * The course version is
 * {@code chain.filter(exchange).then(Mono.fromRunnable(() -> headers.add(...)))},
 * whose runnable fires after the downstream exchange has finished — by which
 * time {@code NettyWriteResponseFilter} may already have committed the response
 * headers for a proxied or streaming response. Those headers are then a
 * {@code ReadOnlyHttpHeaders}, and the add is either silently dropped or
 * throws. This module has already been bitten once by a reactive
 * response-commit problem (see the {@code MaxDirectMemorySize} note in its
 * pom), so it is not a hypothetical risk. {@code beforeCommit} registers a
 * callback that runs while the headers are still mutable.
 *
 * <p>Ordered explicitly at {@link #ORDER}, after
 * {@link RequestTraceFilter#ORDER}. The course registers its response filter as
 * a {@code @Bean} lambda with no order, so Spring sorts it at
 * {@code LOWEST_PRECEDENCE} and it happens to observe the mutated exchange.
 * Give it any order below the request filter's and it captures the
 * pre-mutation exchange instead, reads a null id, and calls
 * {@code headers.add(key, null)} — for which the course has no guard.
 */
@Component
public class ResponseTraceFilter implements GlobalFilter, Ordered {

    /** After {@link RequestTraceFilter#ORDER}, so the id is always established. */
    public static final int ORDER = RequestTraceFilter.ORDER + 1;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            String correlationId = FilterUtility.getCorrelationId(exchange);
            // Explicit guard. A header with a null value is worse than no
            // header: it can throw on write and tells a reader nothing.
            if (correlationId != null && !correlationId.isBlank()) {
                exchange.getResponse().getHeaders()
                        .set(FilterUtility.CORRELATION_ID, correlationId);
            }
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
