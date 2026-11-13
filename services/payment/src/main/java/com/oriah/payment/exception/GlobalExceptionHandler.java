package com.oriah.payment.exception;

import java.net.URI;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Payment's error advice: the shared contract plus the named problems staff are told about when confirming money ({@code reference-already-used},
 * {@code already-voided}, {@code money-recorded-for-claim}...). Extending the shared support makes the starter's default advice back off, so there
 * is still exactly one advice ({@code ErrorAdviceRegistrationTest}); every other exception keeps the shared mapping.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    @ExceptionHandler(PaymentProblem.class)
    public ProblemDetail paymentProblem(PaymentProblem ex) {
        log.debug("{} payment problem {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        ProblemDetail pd = problem(ex.status(), URI.create("https://oriah.dev/problems/" + ex.code()), ex.status().getReasonPhrase(), ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        return pd;
    }
}
