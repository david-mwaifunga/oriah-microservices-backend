package com.oriah.payment.confirmation;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the SHIPPED settings say (the test classpath layers its own over them): the watcher is on in every profile, it passes every 30 seconds and gives
 * a confirmation ten announcements; a confirmation is stuck after five minutes; and the scheduling pool has a thread for each of the three jobs (the
 * outbox relay, the card reconciler and this watcher), because with the default one thread a slow pass of one holds back every payment announcement.
 */
class WatcherSettingsTest {

    private static PropertySource<?> shipped() throws IOException {
        return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
    }

    @Test
    @DisplayName("the shipped file runs the watcher every 30 seconds and stops announcing a confirmation after ten announcements")
    void theWatcherIsOn() throws IOException {
        PropertySource<?> yaml = shipped();

        assertThat(yaml.getProperty("oriah.payment.watcher.enabled")).isEqualTo(true);
        assertThat(yaml.getProperty("oriah.payment.watcher.delay")).isEqualTo(30000);
        assertThat(yaml.getProperty("oriah.payment.watcher.max-emit-attempts")).isEqualTo(10);
    }

    @Test
    @DisplayName("a confirmation is stuck after five minutes, money is in Kwacha and may be dated 72 hours back, and the first card path's switch is gone with it")
    void theSalesSettings() throws IOException {
        PropertySource<?> yaml = shipped();

        assertThat(yaml.getProperty("oriah.sales.settlement-stuck-after")).isEqualTo("PT5M");
        assertThat(yaml.getProperty("oriah.payment.currency")).isEqualTo("ZMW");
        assertThat(yaml.getProperty("oriah.sales.backdate-window")).as("the one 72-hour setting, shared with order").isEqualTo("PT72H");
        assertThat(yaml.getProperty("oriah.payment.backdate-window")).as("payment keeps no window of its own").isNull();
        assertThat(yaml.getProperty("oriah.payment.card.enabled")).as("retired in Phase 13").isNull();
        assertThat(yaml.getProperty("oriah.payment.reconciler.enabled")).as("retired in Phase 13").isNull();
    }

    @Test
    @DisplayName("the scheduling pool has three threads: the relay, the watcher and the ZynlePay checker each get one")
    void threeJobsThreeThreads() throws IOException {
        assertThat(shipped().getProperty("spring.task.scheduling.pool.size")).isEqualTo(3);
    }

    @Test
    @DisplayName("the settings read as the documents say (a smoke check that the keys are where the bound records look for them)")
    void theRecordsBind() {
        var settings = new ConfirmationSettings("ZMW");
        var sales = new SalesSettings(java.time.Duration.ofMinutes(5), java.time.Duration.ofHours(72));
        var watcher = new WatcherSettings(30000, 10);

        assertThat(Map.of("currency", settings.currency(), "stuck", sales.settlementStuckAfter(), "attempts", watcher.maxEmitAttempts()))
                .containsEntry("currency", "ZMW").containsEntry("attempts", 10);
    }
}
