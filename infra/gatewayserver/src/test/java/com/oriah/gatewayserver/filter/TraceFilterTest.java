package com.oriah.gatewayserver.filter;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the correlation-id and response-time filters directly, without a
 * Spring context.
 *
 * <p>Each test here corresponds to a specific defect in the course reference
 * these filters mirror. They are written as unit tests on purpose: the course's
 * entire gateway test suite is a single empty {@code contextLoads()}, which
 * would pass with every one of those defects present.
 */
class TraceFilterTest {

    /** A chain that does nothing but complete, like a terminal filter. */
    private static final GatewayFilterChain NOOP_CHAIN = exchange -> Mono.empty();

    /** How long {@link #DELAYED_CHAIN} stalls, standing in for a slow downstream. */
    private static final Duration CHAIN_DELAY = Duration.ofMillis(40);

    /** A chain that takes a measurable amount of time to complete. */
    private static final GatewayFilterChain DELAYED_CHAIN =
            exchange -> Mono.delay(CHAIN_DELAY).then();

    private final RequestTraceFilter requestTraceFilter = new RequestTraceFilter();
    private final ResponseTraceFilter responseTraceFilter = new ResponseTraceFilter();
    private final ResponseTimeFilter responseTimeFilter = new ResponseTimeFilter();

    // ------------------------------------------------------------------
    // RequestTraceFilter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generates a correlation id when the caller did not send one")
    void generatesCorrelationIdWhenAbsent() {
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/v1/products"));

        requestTraceFilter.filter(exchange, NOOP_CHAIN).block();

        String id = FilterUtility.getCorrelationId(exchange);
        assertThat(id)
                .as("a request with no inbound correlation id must still be traceable")
                .isNotBlank();
    }

    @Test
    @DisplayName("reuses the caller's correlation id instead of minting a new one")
    void reusesInboundCorrelationId() {
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/v1/products")
                .header(FilterUtility.CORRELATION_ID, "caller-supplied-id"));

        requestTraceFilter.filter(exchange, NOOP_CHAIN).block();

        assertThat(FilterUtility.getCorrelationId(exchange))
                .as("an id from upstream must survive the gateway, or a trace "
                        + "started by the frontend cannot be joined to one started here")
                .isEqualTo("caller-supplied-id");
    }

    @Test
    @DisplayName("an empty header value is treated as absent, not propagated as blank")
    void emptyInboundHeaderIsTreatedAsAbsent() {
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/v1/products")
                .header(FilterUtility.CORRELATION_ID, "   "));

        requestTraceFilter.filter(exchange, NOOP_CHAIN).block();

        assertThat(FilterUtility.getCorrelationId(exchange))
                .as("a blank inbound value must be replaced, not forwarded")
                .isNotBlank()
                .isNotEqualTo("   ");
    }

    @Test
    @DisplayName("does not throw when a header is present with no value list")
    void survivesHeaderWithNoValues() {
        // The course's FilterUtility does
        // requestHeaderList.stream().findFirst().get() -- an unguarded
        // Optional.get(). A header present with an empty value list yields
        // NoSuchElementException from inside a GlobalFilter, surfacing as a 500.
        // The header has to be built with an empty value list up front: a
        // MockServerHttpRequest's headers are a ReadOnlyHttpHeaders once built,
        // and put() throws UnsupportedOperationException. (That is the same
        // read-only-headers wall ResponseTraceFilter's beforeCommit exists to
        // stay on the right side of.) The zero-arg varargs form registers the
        // name with no values, which is exactly the shape that makes the
        // course's Optional.get() throw.
        MockServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.get("/api/v1/products")
                        .header(FilterUtility.CORRELATION_ID));

        requestTraceFilter.filter(exchange, NOOP_CHAIN).block();

        assertThat(FilterUtility.getCorrelationId(exchange)).isNotBlank();
    }

    // ------------------------------------------------------------------
    // ResponseTraceFilter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("echoes the correlation id back to the caller")
    void echoesCorrelationIdOnTheResponse() {
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/v1/products"));
        FilterUtility.putCorrelationId(exchange, "round-trip-id");

        responseTraceFilter.filter(exchange, NOOP_CHAIN).block();
        // beforeCommit callbacks run when the response commits, which for a
        // mock exchange means asking it to commit explicitly.
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().getFirst(FilterUtility.CORRELATION_ID))
                .as("without the response header a caller cannot quote the id when reporting a fault")
                .isEqualTo("round-trip-id");
    }

    @Test
    @DisplayName("writes no header, rather than a null one, when no id was established")
    void doesNotWriteNullCorrelationId() {
        // The course version has no null guard: if the response filter is ever
        // ordered before the request filter it calls headers.add(key, null).
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/v1/products"));

        responseTraceFilter.filter(exchange, NOOP_CHAIN).block();
        exchange.getResponse().setComplete().block();

        // getFirst rather than containsKey: in Spring Framework 7 HttpHeaders is
        // no longer a Map, so containsKey/entrySet are gone.
        assertThat(exchange.getResponse().getHeaders().getFirst(FilterUtility.CORRELATION_ID))
                .as("a header with a null value is worse than no header")
                .isNull();
    }

    @Test
    @DisplayName("the response filter runs after the request filter, so an id always exists")
    void responseFilterIsOrderedAfterRequestFilter() {
        // The course leaves the response filter's order implicit (a @Bean
        // lambda at LOWEST_PRECEDENCE) and it only works by accident. Give it
        // any order below the request filter's and the lambda captures the
        // pre-mutation exchange, so getCorrelationId returns null.
        assertThat(requestTraceFilter.getOrder())
                .as("request filter must establish the id before anything reads it")
                .isLessThan(responseTraceFilter.getOrder());
    }

    // ------------------------------------------------------------------
    // ResponseTimeFilter -- THE test that catches the course's frozen constant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("X-Response-Time tracks actual elapsed time, so a slow request reports more")
    void responseTimeIsPerRequestNotPerBoot() {
        // The course writes this as
        //   .addResponseHeader("X-Response-Time", LocalDateTime.now().toString())
        // inside the RouteLocator bean. That expression is evaluated ONCE, while
        // the bean method runs during context refresh, and the resulting String
        // is baked into the filter config -- so every response for the lifetime
        // of the JVM carries the moment the gateway booted. AddResponseHeader
        // only expands ${...} URI template variables, so there is no in-place
        // fix; it has to be a per-request filter.
        //
        // Proving that by comparing two FAST requests does not work: both
        // legitimately round to "0ms", which is what a first version of this
        // test tripped over. The discriminating experiment is to make one
        // request measurably slower and show the header follows -- a frozen
        // constant cannot.
        String fast = responseTimeOf("/api/v1/products", NOOP_CHAIN);
        String slow = responseTimeOf("/api/v1/search", DELAYED_CHAIN);

        long fastMillis = millisIn(fast);
        long slowMillis = millisIn(slow);

        assertThat(slowMillis)
                .as("the slow request waited %dms in the chain, so its header must "
                        + "exceed the fast one's (%s vs %s); equal values mean the value "
                        + "is not being computed per request",
                        CHAIN_DELAY.toMillis(), slow, fast)
                .isGreaterThan(fastMillis);
        assertThat(slowMillis)
                .as("and it must be in the right ballpark, not an arbitrary number")
                .isGreaterThanOrEqualTo(CHAIN_DELAY.toMillis() - 5);
    }

    @Test
    @DisplayName("X-Response-Time reports elapsed milliseconds, not a wall-clock stamp")
    void responseTimeIsADuration() {
        // A duration is what the header name promises and what is actually
        // useful; the course emits a timestamp. Parse it to prove it is numeric.
        String value = responseTimeOf("/api/v1/products", NOOP_CHAIN);

        assertThat(millisIn(value)).isGreaterThanOrEqualTo(0L);
    }

    // ------------------------------------------------------------------

    private String responseTimeOf(String path, GatewayFilterChain chain) {
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get(path));

        responseTimeFilter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        return exchange.getResponse().getHeaders().getFirst(ResponseTimeFilter.RESPONSE_TIME_HEADER);
    }

    private static long millisIn(String headerValue) {
        assertThat(headerValue).as("header must be present and formatted as <n>ms").matches("[0-9]+ms");
        return Long.parseLong(headerValue.replace("ms", ""));
    }

    private static MockServerWebExchange exchangeFor(MockServerHttpRequest.BaseBuilder<?> builder) {
        return MockServerWebExchange.from(builder.build());
    }

    /** Compile-time reminder that these filters operate on the reactive exchange. */
    @SuppressWarnings("unused")
    private static Mono<Void> shapeReminder(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange);
    }
}
