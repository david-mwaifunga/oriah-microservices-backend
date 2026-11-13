package com.oriah.order.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * The settlement job runs by itself (Phase 7): confirmed money whose sale could not be applied the first time is asked about again without anyone
 * asking. Its own context: the schedule is OFF in every other test (a pass landing mid-test would drive the settlements a test holds in flight), and
 * here it is on, quickly.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "oriah.sales.settlement-job.enabled=true", "oriah.sales.settlement-job.delay=200"})
class SettlementJobScheduleTest {

    @MockitoSpyBean
    private SettlementJob job;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the schedule runs the settlement job again and again without anyone asking")
    void theScheduleRuns() throws Exception {
        CountDownLatch passes = new CountDownLatch(3);
        doAnswer(call -> {
            passes.countDown();
            return call.callRealMethod();
        }).when(job).run();

        assertThat(passes.await(10, TimeUnit.SECONDS)).as("three passes within ten seconds at a 200 ms delay").isTrue();
    }
}
