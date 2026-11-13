package com.oriah.catalog.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Inventory refused to tell catalog what the stock cost (401 or 403) although the person asking is allowed to read it: a
 * configuration fault between the two services (a token that expired in between, a role that drifted), not something to retry
 * and not a sign-in problem for the browser. 502, with no {@code Retry-After}, so the screen says something is wrong rather than
 * "try again".
 */
public class CostReadRefusedException extends ResponseStatusException {

    public CostReadRefusedException(Throwable cause) {
        super(HttpStatus.BAD_GATEWAY,
                "Stock refused to say what it cost, so the price was not compared with it and nothing was saved. "
                        + "This is a fault, not a busy moment: tell whoever looks after the system.", cause);
    }
}
