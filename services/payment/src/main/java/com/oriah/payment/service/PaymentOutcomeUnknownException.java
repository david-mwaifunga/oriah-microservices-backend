package com.oriah.payment.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 503: the charge's outcome is not known yet.
 *
 * <p>Raised when the gateway gave no answer (a timeout, an exception), or
 * answered but the answer could not be recorded. Either way the money may or
 * may not have been taken, so this is never reported as a decline. The intent
 * stays CHARGING, which means it can never be charged again; it is settled by
 * asking the gateway about its charge reference.
 *
 * <p>A {@link ResponseStatusException}, so the shared error advice renders it
 * as a 503 problem with this detail and no service-specific advice is needed.
 */
public class PaymentOutcomeUnknownException extends ResponseStatusException {

    public PaymentOutcomeUnknownException(UUID intentId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "The outcome of the charge for payment intent " + intentId
                + " is not known yet. It will not be charged again; its status will be settled shortly.", cause);
    }
}
