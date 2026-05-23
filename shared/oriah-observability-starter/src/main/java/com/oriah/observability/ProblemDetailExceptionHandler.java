package com.oriah.observability;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Cross-service exception handler. Maps the common domain exceptions
 * thrown by the Oriah services to RFC 7807 {@link ProblemDetail}
 * responses ({@code application/problem+json}). Every service that
 * depends on {@code oriah-observability-starter} picks this up
 * automatically through the autoconfig {@code @Import}; no per-
 * service copy needed.
 *
 * <p>Status mapping:
 * <ul>
 *   <li>{@link IllegalArgumentException}             -> 400 BAD_REQUEST
 *   <li>{@link MethodArgumentNotValidException}      -> 400 BAD_REQUEST (with field errors)
 *   <li>{@link EntityNotFoundException}              -> 404 NOT_FOUND
 *   <li>{@link IllegalStateException}                -> 409 CONFLICT  (FSM-illegal transitions, etc.)
 *   <li>everything else                              -> 500 INTERNAL_SERVER_ERROR
 * </ul>
 *
 * <p>Every body carries a {@code traceId} property pulled from the
 * Micrometer-managed SLF4J MDC, so a client receiving a 500 can paste
 * the traceId into Zipkin and see the failing call path immediately.
 *
 * <p>5xx is logged at ERROR; 4xx at DEBUG (the caller already knows
 * they sent something invalid -- no need to spam ops dashboards).
 */
@Slf4j
@RestControllerAdvice
public class ProblemDetailExceptionHandler {

    private static final URI TYPE_BAD_REQUEST = URI.create("https://oriah.dev/problems/bad-request");
    private static final URI TYPE_VALIDATION  = URI.create("https://oriah.dev/problems/validation");
    private static final URI TYPE_NOT_FOUND   = URI.create("https://oriah.dev/problems/not-found");
    private static final URI TYPE_CONFLICT    = URI.create("https://oriah.dev/problems/conflict");
    private static final URI TYPE_INTERNAL    = URI.create("https://oriah.dev/problems/internal-server-error");

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badArgument(IllegalArgumentException ex) {
        log.debug("400 IllegalArgumentException: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(TYPE_BAD_REQUEST);
        pd.setTitle("Bad Request");
        attachTraceId(pd);
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailed(MethodArgumentNotValidException ex) {
        log.debug("400 validation failure: {}", ex.getMessage());
        List<FieldErrorView> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldErrorView(fe.getField(), fe.getDefaultMessage()));
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setType(TYPE_VALIDATION);
        pd.setTitle("Validation Failed");
        pd.setProperty("fieldErrors", fieldErrors);
        attachTraceId(pd);
        return pd;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException ex) {
        log.debug("404 EntityNotFoundException: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Not Found");
        attachTraceId(pd);
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException ex) {
        log.debug("409 IllegalStateException: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(TYPE_CONFLICT);
        pd.setTitle("Conflict");
        attachTraceId(pd);
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unhandled(Exception ex) {
        log.error("500 unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Use the traceId to correlate with logs and traces.");
        pd.setType(TYPE_INTERNAL);
        pd.setTitle("Internal Server Error");
        attachTraceId(pd);
        return pd;
    }

    private static void attachTraceId(ProblemDetail pd) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            pd.setProperty("traceId", traceId);
        }
    }

    /** RFC 7807 extension carrying one Bean Validation field-level violation. */
    public record FieldErrorView(String field, String message) {}
}
