package com.oriah.observability;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A resource a caller asked for does not exist.
 *
 * <p>Message format is the course's, verbatim — {@code "<Resource> not found
 * with the given input data <field> : '<value>'"} — so what you read in
 * section_12 maps onto what this project returns.
 *
 * <h2>Why this one type is shared, when the course duplicates it per service</h2>
 *
 * <p>The course has one service, so duplication costs nothing there. Here an
 * inventory of all 12 found <strong>25 not-found sites across 8 services</strong>,
 * every one meaning exactly the same thing and every one needing to render
 * exactly the same way. Twelve copies of this class would be twelve chances for
 * the status or the problem {@code type} slug to drift, and a client cannot
 * special-case a 404 whose shape depends on which service answered.
 *
 * <p>The argument against shared exception types — "a shared type means a
 * shared status mapping" — is the reason to share <em>this</em> one: the status
 * mapping is the thing that must not vary. What stays per-service is anything
 * whose <em>meaning</em> is local, and those need no shared registration at
 * all, because the status comes from the superclass:
 *
 * <pre>{@code
 * // inventory: inherits 409 from the shared IllegalStateException mapping
 * public class InsufficientStockException extends IllegalStateException { ... }
 *
 * // checkout: carries its own status, so the shared statusCarrying handler
 * // preserves it AND its headers (Retry-After on a 503)
 * public class DownstreamUnavailableException extends ResponseStatusException { ... }
 * }</pre>
 *
 * <p>So the rule is: <strong>share the status mappings, not the domain
 * vocabulary.</strong> A service's own {@code exception/GlobalExceptionHandler}
 * then exists only where it has something to say, and refines the problem
 * {@code type} rather than re-deriving the status.
 *
 * <h2>What this replaced, and why it is not cosmetic</h2>
 *
 * <p>Those 25 sites used {@code ResponseEntity.notFound().build()}, which
 * returns an <strong>empty body</strong>: no detail, no problem {@code type},
 * no {@code traceId}. The tests asserted only the status, which is how it
 * survived review.
 *
 * <p>An empty 404 is a bad answer in three distinct ways. {@code oriah-web}'s
 * {@code apiFetch} parses {@code application/problem+json} and otherwise
 * <em>synthesizes</em> a message from the bare status, so the user saw generic
 * failure text rather than what actually went wrong. Nothing tied the response
 * to a trace, so "the product page 404s sometimes" could not be investigated
 * from a screenshot. And it was indistinguishable from a 404 raised by the
 * gateway for an unmatched route — a missing product and a missing *route*
 * looked identical to the client.
 *
 * <h2>Why it keeps {@code @ResponseStatus} as well as having a handler</h2>
 *
 * <p>{@link OriahExceptionHandlerSupport} claims this type explicitly, so
 * {@code @ResponseStatus} never fires in normal operation and is redundant —
 * which is exactly why it is worth keeping. If the advice were ever absent
 * (a mis-scanned package, a condition that stopped matching, a slice test that
 * excludes advices) {@code ResponseStatusExceptionResolver} still yields 404
 * rather than letting this fall through as a 500. Defence in depth against
 * reporting a missing resource as a server fault — and a 4xx reported as 5xx is
 * retry-eligible, which is the Tier 0.5 defect class.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceName, String fieldName, String fieldValue) {
        super("%s not found with the given input data %s : '%s'"
                .formatted(resourceName, fieldName, fieldValue));
    }
}
