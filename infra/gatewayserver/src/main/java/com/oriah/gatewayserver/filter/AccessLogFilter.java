package com.oriah.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * One log line per request that traverses the gateway:
 * {@code <method> <path> -> <status> in <latency>ms}. Logged at INFO
 * with the Micrometer-injected MDC fields (traceId, spanId, service),
 * so the gateway's stream in Loki is a single chronological audit
 * trail of every request that entered the system -- pivoting from
 * an error or slow span in Zipkin to its surrounding request log
 * line is one filter expression.
 *
 * <p>Ordered last in the filter chain so the status is final
 * (post-routing, post-filters) and the latency is wall-clock end-
 * to-end. Logging on the response side via
 * {@code chain.filter(exchange).then(Mono.fromRunnable(...))} is
 * idiomatic for global filters and avoids blocking the reactive
 * pipeline.
 */
@Component
@Slf4j
public class AccessLogFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    log.info("{} {} -> {} in {}ms",
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getURI().getPath(),
                            status == null ? "?" : status.value(),
                            latencyMs);
                }));
    }

    @Override
    public int getOrder() {
        // Largest int = last to run, so we log the final response
        // status after every other filter has had its say.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
