package com.oriah.promotion.exception;

import java.net.URI;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Promotion's error advice: the shared contract plus the named problems this service explains to its caller ({@code already-stopped},
 * {@code promotion-not-found}...). Extending the shared support makes the starter's default advice back off, so there is still exactly one
 * advice ({@code ErrorAdviceRegistrationTest}).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    @ExceptionHandler(PromotionProblem.class)
    public ProblemDetail promotionProblem(PromotionProblem ex) {
        log.debug("{} promotion problem {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        ProblemDetail pd = problem(ex.status(), URI.create("https://oriah.dev/problems/" + ex.code()), ex.status().getReasonPhrase(), ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        return pd;
    }
}
