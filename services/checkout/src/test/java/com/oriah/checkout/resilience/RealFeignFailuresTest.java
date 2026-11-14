package com.oriah.checkout.resilience;

import java.net.SocketException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.github.tomakehurst.wiremock.http.Fault;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixture behind every retry test makes what it says it makes -- also on a
 * busy machine.
 *
 * <h2>Why a test for a test helper</h2>
 *
 * The retry tests ask {@link RealFeignFailures} for "a 503", "a reset", "a read
 * timeout", and then check what checkout's rules do with it. If the fixture
 * hands back something else, a rule test fails (or passes) for a reason that
 * is not checkout's. That happened: every call once waited at most 300 ms for
 * an answer, so when the machine was busy -- a full build, other builds beside
 * it -- the local server's first 503 took longer than that and came back as a
 * READ TIMEOUT, and "a 503 without Retry-After must be retried" went red for
 * the cart instance with a message about a read timeout.
 *
 * <p>So: an answer, or a dropped connection, that is slow to come from the
 * local server is still that answer or that drop. Only the read timeout itself
 * is meant to give up quickly.
 */
class RealFeignFailuresTest {

    /** Longer than any short wait the fixture once used, as a busy machine's first answer can be. */
    private static final int A_BUSY_MACHINES_DELAY_MS = 1_000;

    private static RealFeignFailures failures;

    @BeforeAll
    static void start() {
        failures = new RealFeignFailures();
    }

    @AfterAll
    static void stop() {
        failures.close();
    }

    @Test
    @DisplayName("a 503 that is slow to arrive is still caught as the 503, not as a read timeout")
    void aSlowAnswerIsStillTheAnswer() {
        RuntimeException slow = failures.answeredAfter(503, null, A_BUSY_MACHINES_DELAY_MS);

        assertThat(ResilientFeignCaller.answeredStatus(slow))
                .as("a 503 the local server took %d ms to send must reach the test as that 503 (%s)",
                        A_BUSY_MACHINES_DELAY_MS, slow)
                .isEqualTo(503);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a dropped connection that is slow to come is still that drop, not a read timeout")
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void aSlowDropIsStillTheDrop(Fault fault) {
        RuntimeException slow = failures.faultAfter(fault, A_BUSY_MACHINES_DELAY_MS);

        assertThat(RealFeignFailures.rootCause(slow))
                .as("%s after %d ms must reach the test as a dropped connection (%s)",
                        fault, A_BUSY_MACHINES_DELAY_MS, slow)
                .isInstanceOf(SocketException.class)
                .isNotInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("the read timeout itself still gives up quickly: in seconds, not the minute Feign waits by default")
    void theReadTimeoutStillGivesUpQuickly() {
        long began = System.nanoTime();
        RuntimeException readTimeout = failures.readTimeout();
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        assertThat(RealFeignFailures.rootCause(readTimeout)).isInstanceOf(SocketTimeoutException.class);
        // A bound well under Feign's own default of 60 s but generous enough for a cold first call on a loaded machine (the client build, the first
        // connection and the stub's dispatch are all inside the timed region).
        assertThat(tookMs).as("a read timeout that waits long makes every test that uses it slow")
                .isLessThan(5_000);
    }
}
