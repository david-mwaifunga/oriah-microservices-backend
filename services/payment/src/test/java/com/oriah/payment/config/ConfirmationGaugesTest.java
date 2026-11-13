package com.oriah.payment.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.payment.confirmation.SettlementWatcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An alert on {@code payment.confirmations.stuck} must not go quiet by itself. A Micrometer gauge holds only a WEAK reference to what it reads, and
 * the reader here is a lambda nothing else refers to, so after the next garbage collection it would answer NaN for ever and an alert on "more than
 * zero" would never fire. Proved on the binder as the service builds it, with a collection forced.
 */
class ConfirmationGaugesTest {

    @Test
    @DisplayName("the stuck-confirmations gauge still reads its number after garbage collection")
    void theGaugeSurvivesGarbageCollection() throws Exception {
        SettlementWatcher watcher = mock(SettlementWatcher.class);
        when(watcher.stuckCount(any(Instant.class))).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ConfirmationGauges().stuckConfirmations(watcher, Clock.fixed(Instant.parse("2026-11-14T08:00:00Z"), ZoneOffset.UTC)).bindTo(registry);
        for (int i = 0; i < 10; i++) {
            System.gc();
            Thread.sleep(50);
        }

        assertThat(registry.get(ConfirmationGauges.STUCK).gauge().value()).isEqualTo(3.0);
    }
}
