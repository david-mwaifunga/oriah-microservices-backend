package com.oriah.payment.service;

/**
 * 500: order refused payment's own request (401, 403, or any 4xx but 404),
 * redirected it, or answered with no order payment can read -- an empty body,
 * a web page, or JSON that is not an order.
 *
 * <p>Payment sends order the very token it has just accepted for this request,
 * asking for one order by id. A refusal there means the two services disagree
 * about tokens, roles or paths -- a fault in how the shop is set up, ours to
 * fix. "That order is not yours" (404) would blame the shopper for it, and a
 * 503 would tell them to keep retrying something that fails every time. So it
 * is left to the shared error advice's catch-all: a 500, logged at ERROR.
 */
public class OrderLookupRejectedException extends RuntimeException {

    public OrderLookupRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
