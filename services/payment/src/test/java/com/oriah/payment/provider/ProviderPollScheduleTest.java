package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.provider.PaymentProvider.Paid;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checker runs by itself (Phase 13): a payment ZynlePay has collected is recorded without anyone asking. The schedule is OFF in every other
 * test (a pass landing mid-test would ask about the very attempts a test is counting; they call the checker themselves) and ON as shipped; here it
 * is on, quickly.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ScriptedProvider.Config.class})
@TestPropertySource(properties = {"eureka.client.enabled=false", "oriah.payment.zynlepay.poll.enabled=true", "oriah.payment.zynlepay.poll.delay=200"})
class ProviderPollScheduleTest {

    @Autowired
    private ScriptedProvider zynlePay;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Environment environment;
    @MockitoBean
    @SuppressWarnings("unused")
    private OrderClient orders;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a payment that is due is asked about, and recorded when ZynlePay says paid, without anyone calling the checker")
    void theScheduleRunsTheChecker() throws Exception {
        UUID id = UUID.randomUUID();
        String reference = ProviderReference.mint();
        zynlePay.statusOf(reference, new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, "MTN"));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, fee_percent,
                                              fee_fixed, status, idempotency_key, request_fingerprint, started_at, sent_at, next_check_at, provider_code)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', 180.00, 'ZMW', 2.5, 0.00, 'PENDING', ?, 'fp', ?, ?, ?, '120')""",
                id, reference, UUID.randomUUID(), "key-" + id, now, now, now);

        long deadline = System.currentTimeMillis() + 15_000;
        while (!"VERIFIED".equals(jdbc.queryForObject("SELECT status FROM provider_payment WHERE id = ?", String.class, id))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM provider_payment WHERE id = ?", String.class, id)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE provider_payment_id = ?", Long.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("as shipped the checker is on, every five seconds, and the pool has a thread for it")
    void asShipped() throws Exception {
        var shipped = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yaml")).getFirst();

        assertThat(shipped.getProperty("oriah.payment.zynlepay.poll.enabled")).isEqualTo(true);
        assertThat(shipped.getProperty("oriah.payment.zynlepay.poll.delay")).isEqualTo(5000);
        assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class)).isEqualTo(3);
    }
}
