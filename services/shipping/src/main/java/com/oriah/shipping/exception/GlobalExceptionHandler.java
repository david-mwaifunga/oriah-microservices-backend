package com.oriah.shipping.exception;

import java.net.URI;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.oriah.observability.OriahExceptionHandlerSupport;
import com.oriah.observability.ProblemTypes;

import lombok.extern.slf4j.Slf4j;

/**
 * Shipping's error contract: the shared one, plus the 422 for a destination
 * outside Zambia. Extending the shared support makes the starter's default
 * advice back off, so there is still exactly one advice
 * (ErrorAdviceRegistrationTest).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {

    /**
     * A request about delivery prices that was understood and cannot be done. The problem's type is its stable name ({@code town-not-offered},
     * {@code stale-version}...), so a screen can switch on it; extra members (the current version, the payers offered) ride along in the body.
     */
    @ExceptionHandler(ShippingProblem.class)
    public ProblemDetail shippingProblem(ShippingProblem ex) {
        log.debug("{} shipping problem {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        ProblemDetail pd = problem(ex.status(), URI.create("https://oriah.dev/problems/" + ex.code()), ex.status().getReasonPhrase(), ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        return pd;
    }

    /**
     * The database is away (no connection, a timeout): shipping cannot say what delivery costs. That is a 503, which checkout and order read
     * as an outage and answer as one -- never a 500 shown as an error to the shopper, and never "free delivery".
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class, TransientDataAccessResourceException.class})
    public ProblemDetail databaseUnavailable(RuntimeException ex) {
        log.error("503 the delivery database is unavailable: {}", ex.toString());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, URI.create("https://oriah.dev/problems/shipping-unavailable"), "Service Unavailable",
                "delivery prices cannot be read right now: try again in a moment");
    }

    /** Logged at INFO: a shopper with an address abroad is an ordinary answer, not a fault. */
    @ExceptionHandler(OutsideDeliveryAreaException.class)
    public ProblemDetail outsideDeliveryArea(OutsideDeliveryAreaException ex) {
        log.info("422 outside the delivery area");
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.OUTSIDE_DELIVERY_AREA,
                "Outside our delivery area", ex.getMessage());
    }
}