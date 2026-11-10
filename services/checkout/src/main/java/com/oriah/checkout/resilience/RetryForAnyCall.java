package com.oriah.checkout.resilience;

import java.util.function.Predicate;

/**
 * What the default retry repeats: only failures after which a second attempt
 * cannot do anything twice -- safe for every call it is used on, including
 * order's {@code POST /orders}, which is not idempotent. That is every call
 * checkout makes but payment's two, which have rules of their own (below).
 *
 * <ul>
 *   <li>the request never arrived: the connection was refused, the host name
 *       did not resolve, or the load balancer had no address for the service
 *       ({@link NetworkFailures#neverArrived});</li>
 *   <li>the service answered 502, 503 or 504 ({@link NetworkFailures#answeredUnavailable})
 *       WITHOUT saying when to come back: it is unavailable and did not do the
 *       work.</li>
 * </ul>
 *
 * <p>Never a 502, 503 or 504 that carries {@code Retry-After}
 * ({@link NetworkFailures#askedToComeBackLater}): order and payment send one
 * only after waiting on their own dependencies, so repeating it multiplies a
 * long wait and leans on a service that has just said it is struggling. The
 * shopper gets checkout's own 503, with checkout's own {@code Retry-After},
 * instead. A 503 that does not say it is still repeated: it means the service
 * did not do the work, and nothing asked to be left alone (NetworkFailures
 * says which of their answers carry it).
 *
 * <p>Never a read timeout, a reset, or a connection closed without an answer:
 * the request may have arrived and been acted on. Never a 500: that is the
 * service's own failure, the same on every attempt. Never a 4xx (the yaml's
 * {@code ignore-exceptions} says so as well).
 *
 * <p>Not used for payment's confirm, the call that charges the card: its 503
 * means "the outcome of the charge is not known yet", so even that answer may
 * follow a charge. Confirm has a rule of its own, {@link RetryForChargingCall}.
 *
 * <p>Named in application.yaml as {@code retry-exception-predicate}, so
 * Resilience4j creates it by its no-argument constructor.
 */
public class RetryForAnyCall implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        return NetworkFailures.neverArrived(failure)
                || (NetworkFailures.answeredUnavailable(failure) && !NetworkFailures.askedToComeBackLater(failure));
    }
}
