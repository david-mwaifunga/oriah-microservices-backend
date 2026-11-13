package com.oriah.inventory.exception;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Inventory's error advice — the course's
 * {@code exception/GlobalExceptionHandler} convention, for this service's own
 * domain exceptions.
 *
 * <p>Inventory is the one service in this tier that has something of its own to
 * declare, which is why it gets a handler at all. Everything generic is
 * inherited from {@link OriahExceptionHandlerSupport}: the RFC 7807 rendering,
 * the {@code traceId} extension, the shared 400/403/404/405/406/409/415/500
 * mapping, and the rule that a status-carrying exception keeps its status. A
 * service with no domain exceptions keeps the shared default advice instead of
 * carrying an empty file around.
 *
 * <h2>What this adds, and what it deliberately does not</h2>
 *
 * <p>Only the problem {@code type}. {@link InsufficientStockException} already
 * inherits <strong>409</strong> from the shared {@code IllegalStateException}
 * mapping — that is the point of extending it — so this handler does not set a
 * status at all. It refines
 * {@code https://oriah.dev/problems/conflict} to
 * {@code https://oriah.dev/problems/insufficient-stock}, which is what lets the
 * checkout saga branch on the <em>reason</em> for a 409 rather than on a
 * message string.
 *
 * <p>That split is the convention worth copying: inherit the status, refine the
 * vocabulary. A per-service handler that re-declares a status is a second place
 * for the two to drift apart.
 *
 * <p>Declaring {@code @RestControllerAdvice} here also makes the starter's
 * {@code ProblemDetailExceptionHandler} back off, keeping exactly one
 * error-contract advice in this context. {@code ErrorAdviceRegistrationTest}
 * asserts that the back-off happens in inventory's real context, and that
 * <em>this</em> class is the one that won.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    static final URI TYPE_INSUFFICIENT_STOCK =
            URI.create("https://oriah.dev/problems/insufficient-stock");

    /**
     * A request about boxes set aside for a product that was understood and cannot be done. The problem's type is its stable name
     * ({@code insufficient-free-packaging}, {@code no-default-box}...), so a screen can switch on it; how many boxes are free rides along as
     * a member of the body.
     */
    @ExceptionHandler(CommitmentProblem.class)
    public org.springframework.http.ProblemDetail commitmentProblem(CommitmentProblem ex) {
        log.debug("{} box commitment problem {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        org.springframework.http.ProblemDetail pd = problem(ex.status(), URI.create("https://oriah.dev/problems/" + ex.code()),
                ex.status().getReasonPhrase(), ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        return pd;
    }

    /**
     * 409, inherited — this method changes only the {@code type}.
     *
     * <p>Logged at DEBUG like every other 4xx. An over-reserve is a normal race
     * on a popular variant, not an incident: two customers checking out the
     * last unit is expected traffic, and logging it at WARN would make a
     * successful sale look like a fault.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail insufficientStock(InsufficientStockException ex) {
        log.debug("409 insufficient stock: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, TYPE_INSUFFICIENT_STOCK, "Conflict", ex.getMessage());
    }
}
