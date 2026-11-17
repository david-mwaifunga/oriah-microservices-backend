package com.oriah.order.service;

import com.oriah.order.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * The correction job runs by itself (Phase 12): a paid cancellation or a return whose stock answer was never heard is asked about again without anyone
 * asking. Its own context: the schedule is OFF in every other test (a pass landing mid-test would finish the corrections a test holds in flight), and
 * here it is on, quickly. It is the fourth scheduled job, so the pool has four threads: a pass waiting on a slow inventory holds nothing else back.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "oriah.sales.correction-job.enabled=true", "oriah.sales.correction-job.delay=200"})
class CorrectionJobScheduleTest {

    @MockitoSpyBean
    private CorrectionJob job;

    @Autowired
    private Environment environment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the schedule runs the correction job again and again without anyone asking")
    void theScheduleRuns() throws Exception {
        CountDownLatch passes = new CountDownLatch(3);
        doAnswer(call -> {
            passes.countDown();
            return call.callRealMethod();
        }).when(job).run();

        assertThat(passes.await(10, TimeUnit.SECONDS)).as("three passes within ten seconds at a 200 ms delay").isTrue();
    }

    @Test
    @DisplayName("four scheduled jobs, four threads: the relay, the hold job, the settlement job and the correction job each get one")
    void fourJobsFourThreads() {
        assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class)).isEqualTo(4);
        assertThat(environment.getProperty("oriah.sales.correction-stuck-after")).isEqualTo("PT5M");
    }
}
