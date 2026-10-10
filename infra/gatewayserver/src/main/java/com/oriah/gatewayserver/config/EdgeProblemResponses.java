package com.oriah.gatewayserver.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Gives 401 and 403 at the edge the same RFC 7807 body every other error has.
 *
 * <h2>Why this class exists (finding #16)</h2>
 *
 * <p>Spring Security's default {@link ServerAuthenticationEntryPoint} and
 * {@link ServerAccessDeniedHandler} answer the exchange <strong>themselves</strong>,
 * before the request ever reaches a controller or a
 * {@code @RestControllerAdvice}. So the shared {@code ProblemDetail} advice
 * never sees them, and the response went out with
 * {@code content-length: 0} — no problem {@code type}, no {@code detail}, no
 * {@code instance}. A caller got a bare status and nothing to act on, where
 * every other error in the platform carries a described failure.
 *
 * <p>Found by capturing the error contract at the edge
 * ({@code scripts/error_goldens.py}), not by reading code: every per-service
 * test asserts what a <em>handler</em> returns, and these two never reach one.
 *
 * <h2>Three things that are easy to get wrong here</h2>
 *
 * <p><strong>{@code WWW-Authenticate} must survive.</strong> It is the one
 * genuinely useful thing the default entry point did, RFC 9110 says a 401 MUST
 * carry it, and replacing the entry point is exactly how it gets dropped. Set
 * explicitly below, with a test that fails if it disappears.
 *
 * <p><strong>There is no {@code traceId} here, and that is not a gap in this
 * class.</strong> It asks the {@link Tracer} and tolerates {@code null}, which
 * in practice is what it always gets. Measured against the running gateway: the
 * response carries no traceId and <em>neither does this class's own log
 * line</em> — which is the tell. There is no span at this point in the chain at
 * all, so the id is not unreachable, it does not yet exist.
 *
 * <p>The cause is ordering. Both the observation filter that opens a span and
 * the gateway's {@code RequestTraceFilter} (a {@code GlobalFilter}, so it runs
 * only for requests that MATCH A ROUTE) sit after the security chain. A request
 * rejected at the edge never routes, so it is never part of a trace. An earlier
 * draft of this javadoc claimed the body would "restore trace correlation";
 * measuring showed there is nothing to correlate to, and the claim is corrected
 * rather than quietly dropped.
 *
 * <p>The lookup stays because it is free and null-safe: if tracing is ever
 * moved ahead of security, the id starts appearing with no change here. The
 * wider consequence — auth failures leave no trace at all, so a
 * credential-stuffing run is invisible in Tempo — is recorded as finding #17
 * and belongs to the observability tier, not to this class.
 *
 * <p><strong>The 403 body does not name the missing role.</strong> Telling a
 * caller "you need CATALOG_ADMIN" turns a denial into a map of the
 * authorization model and points an attacker at the account worth taking. The
 * role that was actually checked is logged; the response says only that a role
 * is missing.
 */
@Component
@Slf4j
public class EdgeProblemResponses {

    private static final URI TYPE_UNAUTHORIZED =
            URI.create("https://oriah.dev/problems/unauthorized");
    private static final URI TYPE_FORBIDDEN =
            URI.create("https://oriah.dev/problems/forbidden");

    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public EdgeProblemResponses(ObjectMapper objectMapper, Tracer tracer) {
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    /**
     * 401. Carries {@code WWW-Authenticate: Bearer} so a client learns the
     * scheme, which is why this does not simply delegate to a generic writer.
     */
    public ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) -> {
            log.debug("401 at the edge for {}: {}",
                    exchange.getRequest().getPath().value(), describe(ex));
            exchange.getResponse().getHeaders()
                    .set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return write(exchange, HttpStatus.UNAUTHORIZED, TYPE_UNAUTHORIZED,
                    "Authentication is required. Send a Bearer token in the "
                            + "Authorization header.");
        };
    }

    /** 403. Deliberately says nothing about which role was required. */
    public ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denied) -> {
            // The role that was checked IS logged. That is the operator's half
            // of the answer; the caller gets the status and a description, and
            // the two are joined by path and time rather than by a traceId --
            // see the class javadoc for why there is no span here.
            log.debug("403 at the edge for {}: {}",
                    exchange.getRequest().getPath().value(), describe(denied));
            return write(exchange, HttpStatus.FORBIDDEN, TYPE_FORBIDDEN,
                    "Authenticated, but this account does not hold a role that "
                            + "permits this request.");
        };
    }

    private static String describe(Exception ex) {
        if (ex instanceof AuthenticationException || ex instanceof AccessDeniedException) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        return String.valueOf(ex);
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, URI type,
                             String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));

        String traceId = currentTraceId();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(problem);
        } catch (Exception e) {                                   // noqa: BLE001
            // Never let a serialization problem turn an auth failure into a
            // 500: the status is the part the client must be able to trust.
            log.warn("could not serialize the {} problem body", status.value(), e);
            return response.setComplete();
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        response.getHeaders().setContentLength(bytes.length);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * The active traceId, or {@code null} outside a span.
     *
     * <p>In practice this is always {@code null} at the edge, because the
     * security chain runs before anything opens a span -- see the class
     * javadoc. Kept null-tolerant and kept at all so that the property appears
     * by itself if tracing ever moves earlier, and so a missing id can never
     * turn a 401 into a 500.
     */
    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
