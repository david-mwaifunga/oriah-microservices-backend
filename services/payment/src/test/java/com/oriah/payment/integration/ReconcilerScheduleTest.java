package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.repository.PaymentIntentRepository;
import com.oriah.payment.service.PaymentReconciler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reconciler runs by itself, on its schedule: nobody has to call it for a
 * lost answer to be settled.
 *
 * <p>Every other test context has the schedule switched off
 * (src/test/resources/config/application.yaml), so a pass can never land in
 * the middle of a test that is asserting a CHARGING intent. This one switches
 * it on, with a short age and delay, and waits for it.
 *
 * <p>It runs beside the outbox relay, which is scheduled too. Spring Boot's
 * scheduler has one thread unless told otherwise, so a reconciler pass
 * waiting on a slow gateway would hold back every payment announcement --
 * and a relay round waiting on a stopped Kafka would hold back
 * reconciliation. Each needs a thread of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "oriah.payment.reconciler.enabled=true",
        "oriah.payment.reconciler.charging-age=1s",
        "oriah.payment.reconciler.delay=200"})
class ReconcilerScheduleTest {

    private static final String ALICE = "kc-alice";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private OutputDestination output;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoSpyBean
    private PaymentGateway gateway;

    @Test
    void aLostAnswerIsSettledWithoutAnyoneAsking() throws Exception {
        // The gateway takes the money; its answer never comes back.
        doAnswer(call -> {
            call.callRealMethod();
            throw new RuntimeException("test: gateway read timed out");
        }).when(gateway).charge(any(), any(), any(), any());
        PaymentIntent intent = intents.saveAndFlush(
                new PaymentIntent(UUID.randomUUID(), ALICE, new BigDecimal("4500.00"), "ZMW"));

        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", intent.id())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject(ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isServiceUnavailable());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long deadline = System.currentTimeMillis() + 15_000;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            state = jdbc.queryForObject("SELECT status FROM payment_intents WHERE id = ?", String.class, intent.id());
            if ("SUCCEEDED".equals(state)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(state).as("settled by the scheduled reconciler within 15 s").isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT actor FROM outbox WHERE aggregate_type = 'payment_intent' AND aggregate_id = ?",
                String.class, intent.id().toString()))
                .isEqualTo(PaymentReconciler.ACTOR);
    }

    @Test
    @DisplayName("a reconciler pass waiting on the gateway does not hold back the relay: another payment is "
            + "announced meanwhile")
    void aSlowReconcilerPassDoesNotHoldBackTheRelay() throws Exception {
        BigDecimal lostAmount = new BigDecimal("1111.11");
        // This one charge's answer is lost, so a scheduled pass asks the
        // gateway about it -- and the gateway is slow to answer.
        doAnswer(call -> {
            call.callRealMethod();
            throw new RuntimeException("test: gateway read timed out");
        }).when(gateway).charge(any(), any(), eq(lostAmount), any());
        CountDownLatch asking = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        doAnswer(call -> {
            asking.countDown();
            answer.await(30, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(gateway).lookup(any());
        try {
            PaymentIntent stuck = pendingFor(lostAmount);
            confirm(stuck.id()).andExpect(status().isServiceUnavailable());
            assertThat(asking.await(15, TimeUnit.SECONDS)).as("a scheduled pass is asking the gateway").isTrue();

            // While that pass waits, another shopper pays.
            PaymentIntent paid = pendingFor(new BigDecimal("4500.00"));
            confirm(paid.id()).andExpect(status().isOk());

            assertThat(publishedWithin10s(paid.id()))
                    .as("payment.succeeded published while the reconciler pass is still waiting on the gateway")
                    .isNotNull();
        } finally {
            answer.countDown();
        }
    }

    // ------------------------------------------------------------------

    /** The relay's message for this intent on oriah.payment, or null if none came within 10 s. */
    private Message<byte[]> publishedWithin10s(UUID intentId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Message<byte[]> message = output.receive(500, "oriah.payment");
            if (message != null
                    && intentId.toString().equals(String.valueOf(message.getHeaders().get("aggregate-id")))) {
                return message;
            }
        }
        return null;
    }

    private PaymentIntent pendingFor(BigDecimal amount) {
        return intents.saveAndFlush(new PaymentIntent(UUID.randomUUID(), ALICE, amount, "ZMW"));
    }

    private ResultActions confirm(UUID intentId) throws Exception {
        return mvc.perform(post("/api/v1/payments/intents/{id}/confirm", intentId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject(ALICE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardNumber\":\"4242424242424242\"}"));
    }
}
