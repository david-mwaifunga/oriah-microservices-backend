package com.oriah.checkout.resilience;

/**
 * A call that failed without any attempt ever reaching the service: so the
 * service did nothing.
 *
 * <p>Thrown by {@link ResilientFeignCaller#callOrThrow} when every attempt was
 * stopped before it left checkout -- the circuit breaker was open, the rate
 * limiter refused, the connection could not even be opened, or the load
 * balancer had no address for the service ({@link NetworkFailures#neverArrived}).
 * The last failure is the cause.
 *
 * <h2>Why a type of its own</h2>
 *
 * For most calls, "it failed" is all the caller needs. For payment's confirm
 * -- the call that charges the card -- it is the difference between two very
 * different things to tell a shopper: "nothing has been charged, try again"
 * (503) and "the charge may or may not have gone through" (502). The thrown
 * exception alone cannot say which: an open breaker on the SECOND attempt,
 * after a first attempt that reached payment, looks the same as one on the
 * first. Only the caller that ran every attempt knows, so it says so with this.
 */
public class NothingSentException extends RuntimeException {

    private final String instance;

    public NothingSentException(String instance, RuntimeException lastFailure) {
        super(instance + " was not called: " + lastFailure.getMessage(), lastFailure);
        this.instance = instance;
    }

    /** The resilience instance whose call was never sent. */
    public String instance() {
        return instance;
    }
}
