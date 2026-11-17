package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.dto.ConfirmPaymentRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The watcher runs by itself, and a pass that is slow does not hold anything else back (Phase 7). The scheduling pool has one thread for each job: the
 * outbox relay (which announces every payment event), the watcher, and the checker that asks ZynlePay about a payment (Phase 13). With fewer, a
 * pass waiting on a slow database would hold back every payment announcement, the very thing the watcher exists to make sure happens.
 *
 * <p>Its own context: the schedule is OFF in every other test (a pass landing mid-test would announce what the test is counting), and here it is on,
 * quickly, with a settlement window of nothing so a confirmation is due at once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "oriah.payment.watcher.enabled=true", "oriah.payment.watcher.delay=200",
        "oriah.sales.settlement-stuck-after=PT0S"})
class WatcherScheduleTest {

    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutputDestination output;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoSpyBean
    private SettlementWatcher watcher;

    private UUID confirmed() {
        return confirmations.confirm(new ConfirmPaymentRequest(UUID.randomUUID(), "MTN", "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                new BigDecimal("1000.00"), new BigDecimal("10.00"), null, null, null, null), "key-" + UUID.randomUUID(), SAM).view().id();
    }

    private long announcements(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'payment_confirmation' AND aggregate_id = ? AND event_type = 'payment.confirmed'",
                Long.class, id.toString());
    }

    @Test
    @DisplayName("the shipped pool has a thread for each of the three jobs")
    void theTestsRunWithThePoolAsShipped() {
        assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("a confirmation nobody answers is announced again without anyone asking: the schedule runs the watcher")
    void theScheduleRuns() throws Exception {
        UUID id = confirmed();

        long deadline = System.currentTimeMillis() + 10_000;
        while (announcements(id) < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(announcements(id)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a slow watcher pass does not hold back the relay: a payment announced meanwhile still reaches the log")
    void aSlowPassDoesNotHoldBackTheRelay() throws Exception {
        CountDownLatch slow = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            slow.countDown();
            release.await(20, TimeUnit.SECONDS);
            return 0;
        }).when(watcher).reemitDue(any());
        try {
            assertThat(slow.await(10, TimeUnit.SECONDS)).as("the watcher is inside its slow call").isTrue();

            UUID id = confirmed();

            assertThat(publishedWithin10s(id)).as("payment.confirmed reached oriah.payment while the watcher was stuck").isNotNull();
        } finally {
            release.countDown();
        }
    }

    private Message<byte[]> publishedWithin10s(UUID id) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Message<byte[]> message = output.receive(500, "oriah.payment");
            if (message != null && id.toString().equals(String.valueOf(message.getHeaders().get("aggregate-id")))) {
                return message;
            }
        }
        return null;
    }
}
