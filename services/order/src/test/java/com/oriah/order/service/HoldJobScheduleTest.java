package com.oriah.order.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.oriah.order.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * The hold job runs by itself (Phase 7), and its own thread means a slow pass does not hold the others back: the scheduling pool has one thread for
 * each job (the outbox relay and this).
 *
 * <p>Its own context: the schedule is OFF in every other test (a pass landing mid-test would give back the PENDING order a test is asserting on), and
 * here it is on, quickly.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "oriah.sales.hold-job.enabled=true", "oriah.sales.hold-job.delay=200"})
class HoldJobScheduleTest {

    @MockitoSpyBean
    private HoldJob job;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the shipped pool has a thread for each of the four jobs (the correction job is the fourth, Phase 12)")
    void theTestsRunWithThePoolAsShipped() {
        assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("the schedule runs the job again and again without anyone asking")
    void theScheduleRuns() throws Exception {
        CountDownLatch passes = new CountDownLatch(3);
        doAnswer(call -> {
            passes.countDown();
            return call.callRealMethod();
        }).when(job).run();

        assertThat(passes.await(10, TimeUnit.SECONDS)).as("three passes within ten seconds at a 200 ms delay").isTrue();
    }
}
