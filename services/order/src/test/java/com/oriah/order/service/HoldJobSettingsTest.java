package com.oriah.order.service;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the SHIPPED settings say (the test classpath layers its own over them, switching the hold job off): the job is on in every profile and runs every
 * 15 seconds; a shopper may hold three orders and twenty pieces; a payment claim stretches the hold to a day; a placement is given up on after a
 * minute; and the scheduling pool has a thread for each of the two jobs (the outbox relay and the hold job), because with the default one thread a
 * slow pass of one holds back the other.
 */
class HoldJobSettingsTest {

    private static PropertySource<?> shipped() throws IOException {
        // The main file, not the test one: getFirst() of the classpath's application.yaml (the main resources come first on a test classpath).
        return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
    }

    @Test
    @DisplayName("the shipped file runs the hold job every 15 seconds in every profile")
    void theJobIsOn() throws IOException {
        assertThat(shipped().getProperty("oriah.sales.hold-job.enabled")).isEqualTo(true);
        assertThat(shipped().getProperty("oriah.sales.hold-job.delay")).isEqualTo(15000);
    }

    @Test
    @DisplayName("a shopper holds at most three orders and twenty pieces; a claim stretches the hold to a day; a placement is given up on after a minute")
    void theSalesSettings() throws IOException {
        PropertySource<?> yaml = shipped();

        assertThat(yaml.getProperty("oriah.sales.max-live-orders")).isEqualTo(3);
        assertThat(yaml.getProperty("oriah.sales.max-held-pieces")).isEqualTo(20);
        assertThat(yaml.getProperty("oriah.sales.payment-submitted-hold")).isEqualTo("PT24H");
        assertThat(yaml.getProperty("oriah.sales.pending-grace")).isEqualTo("PT1M");
    }

    @Test
    @DisplayName("the scheduling pool has three threads: the relay, the hold job and the settlement job each get one")
    void threeJobsThreeThreads() throws IOException {
        assertThat(shipped().getProperty("spring.task.scheduling.pool.size")).isEqualTo(3);
    }

    @Test
    @DisplayName("the settlement job runs every 15 seconds in every profile, and confirmed money is stuck after five minutes")
    void theSettlementJobIsOn() throws IOException {
        assertThat(shipped().getProperty("oriah.sales.settlement-job.enabled")).isEqualTo(true);
        assertThat(shipped().getProperty("oriah.sales.settlement-job.delay")).isEqualTo(15000);
        assertThat(shipped().getProperty("oriah.sales.settlement-stuck-after")).isEqualTo("PT5M");
    }
}
