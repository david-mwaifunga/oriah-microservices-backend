package com.oriah.gatewayserver.filter;

import java.util.UUID;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Establishes the correlation id for every request entering the gateway, and
 * forwards it to the downstream service.
 *
 * <p>A caller-supplied id is reused, so a trace started in the browser joins
 * the one the backend records. Otherwise a fresh id is minted here: the gateway
 * is the first component in the system to see the request, so it is the only
 * place that can guarantee every request has one.
 *
 * <p>Runs at {@link #ORDER} so the id exists before anything can read it.
 * {@link ResponseTraceFilter} and {@code AccessLogFilter} both depend on that
 * ordering, and here it is explicit — the course leaves its response-side
 * counterpart's order implicit, where it works only by accident.
 *
 * <p>No per-request log line is emitted, deliberately. The course logs the id
 * at DEBUG from both trace filters, which in this project would actually print
 * ({@code logging.level.com.oriah.gatewayserver: DEBUG} is set in
 * {@code application.yaml}) and would double the gateway's log volume for no
 * gain. {@code AccessLogFilter} already writes exactly one line per request, so
 * the id is attached to that line instead.
 */
@Component
public class RequestTraceFilter implements GlobalFilter, Ordered {

    /**
     * Early, and expressed relative to {@code HIGHEST_PRECEDENCE} rather than
     * as a small absolute number.
     *
     * <p>This was {@code 1}, which collided with the route filters. Gateway
     * assigns a DSL route's filters orders starting at 1, so a route carrying
     * {@code circuitBreaker(...)} had a filter at the same order as this one,
     * and the tie could resolve either way. When the breaker won, an
     * unreachable downstream forwarded to the fallback BEFORE the correlation
     * id had been established -- so the response carried no id at all, exactly
     * when a user is most likely to be reporting the failure.
     * {@code ResilienceTest.fallbackCarriesCorrelationId} is what caught it.
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = FilterUtility.inboundCorrelationId(exchange);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        // Record on the ORIGINAL exchange. mutate() returns a builder whose
        // exchange delegates attribute storage back to this one, so a write
        // here is visible both downstream and to any filter still holding the
        // original reference.
        FilterUtility.putCorrelationId(exchange, correlationId);

        // Forward it so the 12 services log the same id. Mutating the request
        // means a caller who sent no header still has one downstream.
        ServerWebExchange mutated = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .header(FilterUtility.CORRELATION_ID, correlationId)
                        .build())
                .build();

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
