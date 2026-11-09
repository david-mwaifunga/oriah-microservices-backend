package com.oriah.checkout.resilience;

import java.util.function.Predicate;

/**
 * What the default retry repeats: only failures after which a second attempt
 * cannot do anything twice -- safe for every call checkout makes, including
 * order's {@code POST /orders}, which is not idempotent.
 *
 * <ul>
 *   <li>the request never arrived: the connection was refused, or the host
 *       name did not resolve ({@link NetworkFailures#neverArrived});</li>
 *   <li>the service answered 502, 503 or 504 ({@link NetworkFailures#answeredUnavailable}):
 *       it is unavailable, and for every call but one it did not do the work.</li>
 * </ul>
 *
 * <p>The one: payment's confirm answers 503 when its card gateway gave no
 * answer -- the money MAY have been taken. Repeating it is still safe, but
 * only because of payment: it records the intent as CHARGING before it calls
 * the gateway, and refuses any repeat for an intent in that state with a 409
 * that charges nothing. So the repeat can never succeed; it ends in checkout's
 * 502 "may or may not have gone through" ({@code ConfirmOverRealFeignTest}
 * pins checkout's side of this).
 *
 * <p>Never a read timeout, a reset, or a connection closed without an answer:
 * the request may have arrived and been acted on. Never a 500: that is the
 * service's own failure, the same on every attempt. Never a 4xx (the yaml's
 * {@code ignore-exceptions} says so as well).
 *
 * <p>Named in application.yaml as {@code retry-exception-predicate}, so
 * Resilience4j creates it by its no-argument constructor.
 */
public class RetryForAnyCall implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        return NetworkFailures.neverArrived(failure) || NetworkFailures.answeredUnavailable(failure);
    }
}
