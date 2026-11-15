package com.oriah.finance.exception;

import java.net.URI;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Finance's error advice: the shared contract plus {@link FinanceProblem}, the refusals a caller can act on, each under its own problem type.
 *
 * <p>{@code ErrorAdviceRegistrationTest} pins that exactly one advice extending {@link OriahExceptionHandlerSupport} is registered and that it is this one: the shared
 * starter backs off when a service declares its own. Nothing here echoes a database message: the driver's text names an index and often the clashing value.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    @ExceptionHandler(FinanceProblem.class)
    public ResponseEntity<ProblemDetail> financeProblem(FinanceProblem ex) {
        log.info("{} {}", ex.status().value(), ex.code());
        ProblemDetail pd = problem(ex.status(), URI.create("https://oriah.dev/problems/" + ex.code()), ex.status().getReasonPhrase(), ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        return ResponseEntity.status(ex.status()).body(pd);
    }
}
