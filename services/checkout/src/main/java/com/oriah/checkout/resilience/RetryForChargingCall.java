package com.oriah.checkout.resilience;

import java.util.function.Predicate;

/**
 * What the retry of payment's confirm -- the call that charges the card --
 * repeats: ONLY a request that never arrived ({@link NetworkFailures#neverArrived}):
 * the connection was refused, the host name did not resolve, or the load
 * balancer had no address for payment. Nothing reached payment, so nothing can
 * have been charged, and trying again is safe.
 *
 * <p>Nothing else, not even a 503. Payment answers confirm 503 when its card
 * gateway gave no answer: "the outcome of the charge is not known yet" -- the
 * money may have been taken. Repeating that was safe only because payment
 * refuses the repeat (it marks the intent CHARGING before it charges, and
 * answers any repeat 409), so the repeat could never succeed: it only added a
 * request. One answered failure from confirm ends the checkout as the 502 "may
 * or may not have gone through" ({@code ConfirmOverRealFeignTest} pins it).
 *
 * <p>Named in application.yaml as {@code retry-exception-predicate} on the
 * {@code payment} instance, which only confirm uses.
 */
public class RetryForChargingCall implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        return NetworkFailures.neverArrived(failure);
    }
}
