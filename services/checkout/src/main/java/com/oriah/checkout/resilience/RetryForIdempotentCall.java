package com.oriah.checkout.resilience;

import java.util.function.Predicate;

/**
 * What the retry of an IDEMPOTENT call repeats: everything {@link RetryForAnyCall}
 * does, plus any network failure -- a read timeout, a reset, a connection
 * closed without an answer -- because repeating the call cannot do anything
 * twice.
 *
 * <p>Used by {@code payment-create} alone. Payment hands a repeated create for
 * the caller's own order back the PENDING intent it already made (200), and
 * makes one intent when two identical creates race (commit ffa3187). So when
 * the reply to the first attempt is lost, the retry receives that same intent,
 * and the checkout carries on with it -- rather than answering the shopper 503
 * while their order holds an intent checkout never heard about.
 *
 * <p>Still never a 500 or a 4xx: an answer comes back the same every time.
 */
public class RetryForIdempotentCall implements Predicate<Throwable> {

    private final RetryForAnyCall anyCall = new RetryForAnyCall();

    @Override
    public boolean test(Throwable failure) {
        return anyCall.test(failure) || NetworkFailures.noCompleteAnswer(failure);
    }
}
