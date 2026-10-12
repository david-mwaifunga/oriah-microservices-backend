package com.oriah.catalog.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.catalog.model.AttributeValidationException;
import com.oriah.observability.OriahExceptionHandlerSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Catalog's error contract: the shared one, plus the two exception types only
 * this service raises.
 *
 * <h2>Why catalog now owns an advice at all</h2>
 *
 * Until now catalog had no domain exceptions of its own, so the shared
 * {@code ProblemDetailExceptionHandler} rendered everything. The products
 * phase introduced two exceptions the shared advice cannot handle, for
 * opposite reasons.
 *
 * <p>{@link DataIntegrityViolationException} lives in {@code org.springframework.dao}
 * — that is, in spring-tx, which the observability starter deliberately does
 * NOT depend on. This repo keeps the non-JPA services (gateway, eureka,
 * configserver, shipping, checkout) free of spring-data and spring-tx, and
 * putting a DAO handler in the shared starter would force that dependency on
 * all twelve. So it belongs here, where JPA already is. If a second JPA
 * service needs it, it moves to a shared JPA advice at that point rather than
 * being generalised before there is a second case.
 *
 * <p>{@link AttributeValidationException} is catalog-specific by nature: no
 * other service has type-driven product attributes.
 *
 * <p>{@code ErrorAdviceRegistrationTest} pins that exactly one advice
 * extending {@link OriahExceptionHandlerSupport} is registered, and names
 * which one. Its {@code EXPECTED_ADVICE} moves to this class, which is the
 * mechanism that class documents: the shared starter backs off when a service
 * declares its own.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    /**
     * A database constraint rejected the write.
     *
     * <p>409, not 500. A unique or foreign-key violation means the request
     * conflicts with data that already exists, which is the caller's situation
     * to resolve rather than a server fault. Before this handler, the
     * exception fell through to {@code @ExceptionHandler(Exception.class)} and
     * surfaced as "Internal Server Error" — found by creating two products
     * whose titles reduce to the same slug, which answered 201 then 500.
     *
     * <p>{@code ProductService} resolves the slug case before it reaches here
     * and retries once on a lost race, so in practice this handler covers the
     * conflicts catalog does NOT know how to resolve — a duplicate SKU, say.
     * It is the floor, not the intended answer.
     *
     * <p>The detail is deliberately generic. The driver's message names the
     * constraint, the table, and often the conflicting VALUE, so echoing it
     * would turn a write error into a disclosure oracle: a caller could probe
     * which SKUs exist by trying to create them. The specifics are logged for
     * an operator instead.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail dataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("409 data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict",
                "The request conflicts with existing data.");
    }

    /**
     * Type-specific product attributes failed validation.
     *
     * <p>Rendered with a {@code fieldErrors} array in the same shape bean
     * validation produces, so an admin form highlights the offending inputs
     * without needing a second code path for this one case. That matters
     * because the attribute set is dynamic — the form is generated from the
     * product type's definitions — so a single top-level message would leave
     * the client unable to say which of a watch's six spec fields was wrong.
     *
     * <p>Declared here even though {@link AttributeValidationException}
     * extends {@code IllegalArgumentException}, which the shared advice
     * already maps to 400: a subclass handler wins over an inherited handler
     * for a supertype, so this upgrades the body without changing the status.
     */
    @ExceptionHandler(AttributeValidationException.class)
    public ProblemDetail attributeValidationFailed(AttributeValidationException ex) {
        log.debug("400 attribute validation failure: {}", ex.getMessage());
        List<FieldErrorView> fieldErrors = new ArrayList<>();
        for (Map.Entry<String, String> error : ex.fieldErrors()) {
            fieldErrors.add(new FieldErrorView(error.getKey(), error.getValue()));
        }
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION,
                "Validation Failed", "Product attribute validation failed");
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }
}
