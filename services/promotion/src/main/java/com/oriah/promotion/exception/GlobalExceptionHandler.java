package com.oriah.promotion.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Promotion's error advice: the shared contract plus the one database failure
 * this service can explain to its caller, a second promotion with a code that
 * already exists.
 *
 * <h2>Why a handler of its own, and why so narrow</h2>
 *
 * <p>{@link DataIntegrityViolationException} lives in spring-tx, which the shared
 * observability starter deliberately does not depend on, so the shared advice has
 * no handler for it. Without one, creating a promotion with a code that exists
 * answered 500: the {@code code} column is unique, nothing checked before the
 * insert, and the database's refusal came out as an unexplained server error (with
 * a stack trace in the log) for what is an ordinary admin typo.
 *
 * <p>Only that one case is a 409. It is recognised by the PostgreSQL error code
 * (23505, unique violation) <em>and</em> the constraint's name, so a unique
 * violation on some other column added later is not silently reported as a
 * duplicate code. Everything else (a missing value, a check the code should have
 * caught first) is OUR bug, not the caller's conflict to resolve, and goes to the
 * shared catch-all: logged with its stack trace and answered with the platform's
 * generic 500.
 *
 * <p>The 409's detail is deliberately generic. The driver's message names the
 * constraint and the clashing value, so echoing it would turn a write error into a
 * disclosure oracle; it is logged for an operator instead.
 *
 * <p>{@code ErrorAdviceRegistrationTest} pins that exactly one advice extending
 * {@link OriahExceptionHandlerSupport} is registered and that it is this one: the
 * shared starter backs off when a service declares its own.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    /** PostgreSQL's SQLSTATE for a unique violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** The name PostgreSQL gave the {@code UNIQUE} on {@code code} in V1__create_promotions.sql. */
    private static final String CODE_CONSTRAINT = "promotions_code_key";

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> dataIntegrityViolation(DataIntegrityViolationException ex) {
        if (isDuplicateCode(ex)) {
            log.warn("409 a promotion with this code already exists: {}", ex.getMostSpecificCause().getMessage());
            return new ResponseEntity<>(
                    problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict",
                            "A promotion with this code already exists."),
                    HttpStatus.CONFLICT);
        }
        return unhandled(ex);
    }

    /** Both the error code and the constraint name must match, anywhere along the cause chain. */
    private static boolean isDuplicateCode(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation
                    && UNIQUE_VIOLATION.equals(violation.getSQLState())
                    && CODE_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
