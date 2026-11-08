package com.oriah.shipping.exception;

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

    /** Logged at INFO: a shopper with an address abroad is an ordinary answer, not a fault. */
    @ExceptionHandler(OutsideDeliveryAreaException.class)
    public ProblemDetail outsideDeliveryArea(OutsideDeliveryAreaException ex) {
        log.info("422 outside the delivery area");
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.OUTSIDE_DELIVERY_AREA,
                "Outside our delivery area", ex.getMessage());
    }
}