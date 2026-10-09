package com.oriah.gatewayserver.filter;

import org.slf4j.MDC;
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
 * an error or slow span in Tempo to its surrounding request log
 * line is one filter expression.
 *
 * <p>Also carries the gateway's correlation id as an MDC field, so the
 * single line per request is the one place that id is greppable.
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

    /**
     * MDC key for the correlation id. Our logback config renders every MDC
     * entry into the JSON log line via a bare {@code <mdc/>} provider, so the
     * key chosen here IS the JSON field name a Loki query filters on.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

                    // The correlation id reaches the log line through the MDC,
                    // bound HERE and only here rather than in RequestTraceFilter.
                    //
                    // Why not bind it where it is created: this is WebFlux, and
                    // MDC is a ThreadLocal. An MDC.put in RequestTraceFilter
                    // would be set on whichever event-loop thread ran that
                    // filter, and this log statement runs later, potentially on
                    // a different one -- the value would be missing here and
                    // leaked on the other thread. Nothing in this repo enables
                    // automatic context propagation, so there is no machinery
                    // carrying it across the hop. The id travels in the exchange
                    // attributes instead (see FilterUtility), and is bound to
                    // the MDC for exactly the duration of this one log call.
                    //
                    // The course instead interpolates the id into its own
                    // debug lines, which keeps it out of every OTHER line --
                    // errors, Netty warnings, security denials -- and so cannot
                    // be grepped end to end.
                    String correlationId = FilterUtility.getCorrelationId(exchange);
                    if (correlationId != null) {
                        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
                    }
                    try {
                        log.info("{} {} -> {} in {}ms",
                                exchange.getRequest().getMethod(),
                                exchange.getRequest().getURI().getPath(),
                                status == null ? "?" : status.value(),
                                latencyMs);
                    }
                    finally {
                        // Event-loop threads are pooled and long-lived: a key
                        // left behind here would reappear on an unrelated
                        // request's log line, which is worse than no id at all.
                        MDC.remove(CORRELATION_ID_MDC_KEY);
                    }
                }));
    }

    @Override
    public int getOrder() {
        // Largest int = last to run, so we log the final response
        // status after every other filter has had its say.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
