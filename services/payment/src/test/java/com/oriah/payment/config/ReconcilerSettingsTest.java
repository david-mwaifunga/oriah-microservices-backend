package com.oriah.payment.config;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.oriah.payment.service.PaymentReconciler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reconciler's settings as the service ships them, read straight from
 * src/main/resources/application.yaml.
 *
 * <p>Pinned because the tests run with the schedule OFF (their own
 * config/application.yaml overrides it), so no Spring test would notice if
 * the shipped file stopped switching it on -- and a payment service without
 * its reconciler leaves every lost answer CHARGING for good, with money that
 * may have been taken and an order that never hears so.
 */
class ReconcilerSettingsTest {

    @Test
    @DisplayName("application.yaml switches the reconciler on, every 30 s, for charges in flight over 2 minutes")
    void theShippedServiceReconciles() throws IOException {
        PropertySource<?> shipped = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"))
                .get(0);

        assertThat(shipped.getProperty("oriah.payment.reconciler.enabled")).isEqualTo(true);
        assertThat(String.valueOf(shipped.getProperty("oriah.payment.reconciler.charging-age"))).isEqualTo("120s");
        assertThat(shipped.getProperty("oriah.payment.reconciler.delay")).isEqualTo(30000);
    }

    @Test
    @DisplayName("a charging-age of zero or less is refused at startup")
    void aZeroAgeIsRefused() {
        // With no age, a confirm still waiting on the gateway would be voided
        // under it, failing a payment that was about to go through.
        for (Duration age : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new PaymentReconciler(null, null, null, null, Clock.systemUTC(), age))
                    .as("charging-age %s", age)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("oriah.payment.reconciler.charging-age");
        }
    }
}
