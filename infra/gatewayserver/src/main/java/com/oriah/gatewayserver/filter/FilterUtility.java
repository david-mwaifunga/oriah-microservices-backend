package com.oriah.gatewayserver.filter;

import org.springframework.web.server.ServerWebExchange;

/**
 * Shared access to the correlation id that the gateway attaches to every
 * request, mirroring the course's {@code FilterUtility}.
 *
 * <p><strong>The id lives in the exchange ATTRIBUTES, not in a ThreadLocal.</strong>
 * This matters more than it looks. The gateway is WebFlux: one request is
 * handled across whatever event-loop threads Reactor picks, so an
 * {@code MDC.put} in one filter is not visible to a log statement in a later
 * operator. Nothing in this repo enables automatic context propagation (no
 * {@code Hooks.enableAutomaticContextPropagation()}, no
 * {@code ThreadLocalAccessor}), and turning that on globally to carry one
 * string would be a large hammer. Exchange attributes travel with the exchange
 * by construction, need no propagation machinery, and are readable from any
 * filter holding it. MDC is therefore bound only for the instant a log line is
 * actually written, in {@code AccessLogFilter}.
 *
 * <p>Deliberate differences from the course version:
 * <ul>
 *   <li>Its {@code getCorrelationId} is
 *       {@code requestHeaderList.stream().findFirst().get()} — an unguarded
 *       {@code Optional.get()}. A header present with an empty value list
 *       throws {@code NoSuchElementException} from inside a
 *       {@code GlobalFilter}, which reaches the caller as a 500. The read here
 *       is null-safe, and also treats a blank value as absent.</li>
 *   <li>The header is read once rather than twice (the course does one map
 *       lookup to test presence and another to fetch).</li>
 * </ul>
 */
public final class FilterUtility {

    /**
     * Lower-case and hyphenated, with no {@code X-} prefix: RFC 6648
     * deprecated that convention. The course uses
     * {@code eazybank-correlation-id}; this is the same shape under our name.
     */
    public static final String CORRELATION_ID = "oriah-correlation-id";

    /**
     * Attribute key, deliberately distinct from the header name so the two
     * cannot be confused at a call site.
     */
    private static final String CORRELATION_ID_ATTRIBUTE =
            FilterUtility.class.getName() + ".correlationId";

    private FilterUtility() {
    }

    /** The correlation id established for this exchange, or {@code null}. */
    public static String getCorrelationId(ServerWebExchange exchange) {
        return exchange.getAttribute(CORRELATION_ID_ATTRIBUTE);
    }

    /** Records the id for the rest of this exchange's lifetime. */
    public static void putCorrelationId(ServerWebExchange exchange, String correlationId) {
        exchange.getAttributes().put(CORRELATION_ID_ATTRIBUTE, correlationId);
    }

    /**
     * The inbound correlation id, or {@code null} when the caller sent none,
     * sent it blank, or sent the header with no values at all.
     */
    public static String inboundCorrelationId(ServerWebExchange exchange) {
        // getFirst is null-safe for both "header absent" and "header present
        // with an empty value list". List.get(0) and Optional.get() are not.
        String inbound = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID);
        return (inbound == null || inbound.isBlank()) ? null : inbound;
    }
}
