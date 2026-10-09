package com.oriah.gatewayserver.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.oriah.gatewayserver.filter.FilterUtility;

/**
 * Where the circuit-breaker filter forwards a request when a downstream
 * service is unreachable or too slow, mirroring the course's
 * {@code /contactSupport} endpoint.
 *
 * <p>Without it, a tripped breaker surfaces whatever Spring Cloud Gateway
 * happens to raise — which, before the Tier 0.5 error-contract fix, was a 500
 * for what is really a 503. A caller cannot tell "this service is down, try
 * again shortly" from "the server is broken" and, worse, a 500 is
 * retry-eligible while a 503 with no {@code Retry-After} is at least honest
 * about the state.
 *
 * <h2>Why it answers every HTTP method</h2>
 *
 * <p>{@code @RequestMapping} with no {@code method} attribute handles GET,
 * POST, PUT, PATCH and DELETE alike. That is deliberate, not an oversight
 * inherited from the course: the breaker forwards here regardless of the
 * original verb, so restricting this to GET would turn a failed POST into a
 * 405 — a response that is both wrong and actively misleading, because it
 * suggests the caller used the wrong method rather than that the backend is
 * down.
 *
 * <h2>Body shape</h2>
 *
 * <p>RFC 7807 {@code application/problem+json}, matching every other error in
 * this system (the shared {@code ProblemDetailExceptionHandler} produces the
 * same shape, and {@code oriah-web}'s {@code problemFromResponse} already
 * parses it). The course returns a plain string, which the frontend's typed
 * client would surface as a schema mismatch rather than a readable message.
 *
 * <p>The correlation id is echoed into the body as well as the header, because
 * this is precisely the response a user is most likely to screenshot and send
 * to support.
 */
@RestController
public class FallbackController {

    /** Matches the {@code fallbackUri} configured on the circuit-breaker filters. */
    public static final String FALLBACK_PATH = "/contactSupport";

    private static final URI PROBLEM_TYPE =
            URI.create("https://oriah.dev/problems/service-unavailable");

    @RequestMapping(FALLBACK_PATH)
    public ResponseEntity<ProblemDetail> contactSupport(ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "That part of the store is temporarily unavailable. Please try again in a moment.");
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Service Unavailable");

        String correlationId = FilterUtility.getCorrelationId(exchange);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(problem);
    }
}
