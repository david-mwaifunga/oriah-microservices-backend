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

    /** The property a problem carries when the caller should come back later: it is sent as the {@code Retry-After} header, not in the body. */
    private static final String RETRY_AFTER = "retryAfterSeconds";

    @ExceptionHandler(PaymentProblem.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> paymentProblem(PaymentProblem ex) {
        log.debug("{} payment problem {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        ProblemDetail pd = problem(ex.status(), URI.create("https://oriah.dev/problems/" + ex.code()), ex.status().getReasonPhrase(), ex.getMessage());
        org.springframework.http.ResponseEntity.BodyBuilder response = org.springframework.http.ResponseEntity.status(ex.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
        ex.properties().forEach((name, value) -> {
            if (RETRY_AFTER.equals(name)) {
                response.header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(value));
            } else {
                pd.setProperty(name, value);
            }
        });
        return response.body(pd);
    }
}
