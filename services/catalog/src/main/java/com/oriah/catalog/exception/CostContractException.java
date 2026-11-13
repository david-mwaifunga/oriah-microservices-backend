package com.oriah.catalog.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Inventory answered the held-cost read in a way catalog does not understand: a variant left out, an entry with no id, a count that does
 * not match, a status nobody expects. The two services disagree about the contract, so nothing was compared and nothing is saved: a plain
 * 500, never the 409 conflict a bare {@code IllegalStateException} would become, and never "no cost known".
 *
 * <p>The detail may name an id but never carries inventory's body.
 */
public class CostContractException extends ResponseStatusException {

    public CostContractException(String detail, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, detail, cause);
    }
}
