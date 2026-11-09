package com.oriah.payment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The life of one payment intent:
 *
 * <pre>
 *   PENDING --startCharging(ref)--&gt; CHARGING --markSucceeded(gwRef, at)--&gt; SUCCEEDED
 *                                          \--markFailed(reason, at)----&gt; FAILED
 * </pre>
 *
 * CHARGING is the new step, and the reason it exists is the order of events:
 * the intent is recorded as "being charged, with this reference" and that
 * record is committed BEFORE the gateway is called. From then on a retry can
 * see a charge is in flight and must not start another, and nothing can move
 * the intent to SUCCEEDED or FAILED except an answer about that charge.
 */
class PaymentIntentTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-11-09T07:30:00.123456Z");

    private static PaymentIntent pending() {
        return new PaymentIntent(UUID.randomUUID(), "kc-alice", new BigDecimal("4500.00"), "ZMW");
    }

    @Test
    @DisplayName("charging records the reference the gateway will be called with")
    void startChargingRecordsTheReference() {
        PaymentIntent intent = pending();
        UUID reference = UUID.randomUUID();

        intent.startCharging(reference);

        assertThat(intent.status()).isEqualTo(PaymentStatus.CHARGING);
        assertThat(intent.chargeReference()).isEqualTo(reference);
    }

    @Test
    @DisplayName("a charge needs a reference: without one nobody could ever ask how it ended")
    void startChargingNeedsAReference() {
        assertThatThrownBy(() -> pending().startCharging(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an intent already being charged cannot be charged again")
    void chargingTwiceIsRefused() {
        PaymentIntent intent = pending();
        UUID first = UUID.randomUUID();
        intent.startCharging(first);

        assertThatThrownBy(() -> intent.startCharging(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is being charged");
        assertThat(intent.chargeReference()).isEqualTo(first);
    }

    @Test
    @DisplayName("a settled intent cannot be charged again")
    void aSettledIntentIsNotCharged() {
        PaymentIntent intent = pending();
        intent.startCharging(UUID.randomUUID());
        intent.markFailed("card declined", CAPTURED_AT);

        assertThatThrownBy(() -> intent.startCharging(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in terminal state FAILED");
    }

    @Test
    @DisplayName("a capture records the gateway's reference and the gateway's time, not ours")
    void markSucceededTakesTheGatewaysWord() {
        PaymentIntent intent = pending();
        intent.startCharging(UUID.randomUUID());

        intent.markSucceeded("fake_ch_1", CAPTURED_AT);

        assertThat(intent.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(intent.gatewayReference()).isEqualTo("fake_ch_1");
        assertThat(intent.confirmedAt()).isEqualTo(CAPTURED_AT);
    }

    @Test
    @DisplayName("a decline records the gateway's reason and the gateway's time")
    void markFailedTakesTheGatewaysWord() {
        PaymentIntent intent = pending();
        intent.startCharging(UUID.randomUUID());

        intent.markFailed("card declined", CAPTURED_AT);

        assertThat(intent.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(intent.failureReason()).isEqualTo("card declined");
        assertThat(intent.confirmedAt()).isEqualTo(CAPTURED_AT);
    }

    @Test
    @DisplayName("nothing settles an intent that was never charged")
    void onlyAChargingIntentSettles() {
        assertThatThrownBy(() -> pending().markSucceeded("fake_ch_1", CAPTURED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not being charged");
        assertThatThrownBy(() -> pending().markFailed("card declined", CAPTURED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not being charged");
    }

    @Test
    @DisplayName("a settlement must say who decided it and when")
    void aSettlementNeedsTheGatewaysFacts() {
        PaymentIntent intent = pending();
        intent.startCharging(UUID.randomUUID());

        assertThatThrownBy(() -> intent.markSucceeded(null, CAPTURED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intent.markSucceeded("fake_ch_1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intent.markFailed(" ", CAPTURED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intent.markFailed("card declined", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(intent.status()).isEqualTo(PaymentStatus.CHARGING);
    }
}
