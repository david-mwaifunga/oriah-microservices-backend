package com.oriah.order.exception;

/**
 * The repriced order sums to more than the money column can hold.
 *
 * <p>The per-line caps bound each input -- 100 lines, 99 units, a title length
 * -- but nothing bounded the SUM, and catalog's {@code unit_price} has a floor
 * with no ceiling. 100 lines x 99 units x a mistyped admin price overflows
 * {@code orders.subtotal NUMERIC(12,2)} and used to become a 500 from the
 * insert.
 *
 * <p>422 rather than 409: the request is well-formed and the state is not in
 * conflict -- the order is simply larger than this system can represent.
 *
 * <p>No figure in the message. The cap is an implementation limit, and a
 * caller who reached it learns nothing actionable from the number.
 */
public class TotalTooLargeException extends RuntimeException {

    public TotalTooLargeException() {
        super("This order is too large to process. Please split it into smaller orders.");
    }
}
