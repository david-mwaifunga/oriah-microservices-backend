package com.oriah.procurement.exception;

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
 * Procurement's error advice: the shared contract plus the one database failure
 * this service can explain to its caller.
 *
 * <h2>Why a handler of its own, and why so narrow</h2>
 *
 * <p>{@link DataIntegrityViolationException} lives in spring-tx, which the shared
 * observability starter deliberately does not depend on (it would drag spring-data
 * into the gateway and the services with no database), so the shared advice has no
 * handler for it. Without one, a duplicate supplier name that gets past the
 * service's own check answers 500, and it does get past it: two requests can both
 * run the check before either inserts, and in the unit tests' race seven of eight
 * simultaneous creates did.
 *
 * <p>Catalog handles the exception by turning <em>every</em> integrity violation
 * into a 409. That is too wide here. Procurement will grow foreign keys in Phase 4,
 * and a purchase order pointing at nothing is OUR bug, to be paged, not the
 * caller's conflict to resolve. So only one case is a 409: SQLSTATE 23505 (unique
 * violation) on the index {@code uq_suppliers_name_live}. Both must match.
 * Everything else goes to the shared catch-all, which logs it with its stack trace
 * and answers the platform's generic 500.
 *
 * <p>The 409's detail is deliberately generic. The driver's message names the
 * index and often the clashing value, so echoing it would turn a write error into
 * a disclosure oracle; it is logged for an operator instead.
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

    /** The partial unique index in V1__create_suppliers.sql. */
    private static final String LIVE_NAME_INDEX = "uq_suppliers_name_live";

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> dataIntegrityViolation(DataIntegrityViolationException ex) {
        if (isLiveNameCollision(ex)) {
            log.warn("409 a live supplier already has this name: {}", ex.getMostSpecificCause().getMessage());
            return new ResponseEntity<>(
                    problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict",
                            "A supplier with this name already exists."),
                    HttpStatus.CONFLICT);
        }
        return unhandled(ex);
    }

    /** The same 400 {@code validation} body {@code @Valid} produces, for the edit's per-field checks. */
    @ExceptionHandler(InvalidFieldsException.class)
    public ProblemDetail invalidFields(InvalidFieldsException ex) {
        log.debug("400 validation failure on {} field(s)", ex.fieldErrors().size());
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "Validation Failed",
                "Request validation failed");
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    /** Both the error code and the index name must match, anywhere along the cause chain. */
    private static boolean isLiveNameCollision(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation
                    && UNIQUE_VIOLATION.equals(violation.getSQLState())
                    && LIVE_NAME_INDEX.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
