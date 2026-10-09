package com.oriah.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Adds a per-request {@code X-Response-Time} header reporting how long the
 * gateway took to serve the request.
 *
 * <p><strong>This is a filter specifically because the course's version cannot
 * work.</strong> There it is written inside the {@code RouteLocator} bean as:
 *
 * <pre>{@code .addResponseHeader("X-Response-Time", LocalDateTime.now().toString())}</pre>
 *
 * <p>{@code LocalDateTime.now().toString()} is an ordinary Java expression,
 * evaluated eagerly exactly once, while the {@code @Bean} method body runs
 * during context refresh. The resulting String is then baked into the
 * {@code AddResponseHeader} filter config, so every response for the lifetime
 * of the JVM carries the same value: the moment the gateway booted. And
 * {@code AddResponseHeader} only expands {@code ${...}} URI-template
 * variables, so there is no in-place fix — a genuinely per-request value has to
 * be computed inside request scope.
 * {@code TraceFilterTest.responseTimeIsPerRequestNotPerBoot} is the assertion
 * that tells the two implementations apart.
 *
 * <p>It also emits a DURATION rather than a wall-clock stamp, which is what the
 * header name promises and the more useful of the two: a caller can see the
 * gateway took 340ms without having to know when their request started.
 *
 * <p>Ordered first so the measurement spans the whole filter chain, and written
 * through {@code beforeCommit} so the header is set while the response headers
 * are still mutable (see {@link ResponseTraceFilter}).
 */
@Component
public class ResponseTimeFilter implements GlobalFilter, Ordered {

    public static final String RESPONSE_TIME_HEADER = "X-Response-Time";

    /** First, so the elapsed time covers everything that follows. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // nanoTime, not currentTimeMillis: monotonic, so an NTP step mid-request
        // cannot produce a negative duration.
        long startNanos = System.nanoTime();

        exchange.getResponse().beforeCommit(() -> {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            exchange.getResponse().getHeaders()
                    .set(RESPONSE_TIME_HEADER, elapsedMillis + "ms");
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
