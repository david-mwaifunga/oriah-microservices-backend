package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.json.JsonMapper;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.client.OrderClient;
import com.oriah.payment.provider.PaymentProvider.Failed;
import com.oriah.payment.provider.PaymentProvider.Paid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Two cases the code reviews found (Phase 13), in a context of their own: a tariff with a fixed part (K5.00 on an MTN payment, as a merchant
 * agreement may have), and a checker the test can step in behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScriptedProvider.Config.class})
@TestPropertySource(properties = {"eureka.client.enabled=false", "oriah.payment.zynlepay.fees.MTN.percent=2.5", "oriah.payment.zynlepay.fees.MTN.fixed=5.00",
        "oriah.payment.zynlepay.fees.AIRTEL.percent=2.5", "oriah.payment.zynlepay.fees.ZAMTEL.percent=2.5", "oriah.payment.zynlepay.fees.CARD.percent=3.5"})
class ProviderReviewFixesTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScriptedProvider zynlePay;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private ProviderPaymentChecks checks;
    @MockitoBean
    private OrderClient orders;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private String problem(MvcResult result) throws Exception {
        String type = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("type").asString();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private UUID attempt(String status, String reason, String amount) {
        UUID id = UUID.randomUUID();
        Timestamp sent = Timestamp.from(Instant.now().minus(Duration.ofMinutes(40)));
        jdbc.update("""
                INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, fee_percent,
                                              fee_fixed, status, unresolved_reason, idempotency_key, request_fingerprint, started_at, sent_at, unresolved_at)
                VALUES (?, 'ZYNLEPAY', ?, ?, 'kc-alice', 'MOBILE_MONEY', 'MTN', '+260961234567', ?, 'ZMW', 2.5, 5.00, ?, ?, ?, 'fp', ?, ?, ?)""",
                id, ProviderReference.mint(), UUID.randomUUID(), new BigDecimal(amount), status, reason, "key-" + id, sent, sent,
                reason == null ? null : Timestamp.from(Instant.now()));
        return id;
    }

    private MvcResult resolve(UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/payments/provider-payments/" + id + "/resolve")
                .with(jwt().jwt(j -> j.subject("kc-olive")).authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"), new SimpleGrantedAuthority("ROLE_SALES_AGENT")))
                .header("Idempotency-Key", "res-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    @DisplayName("an order of K3.00 by MTN, where the fee would be K5.08: the payment is not started, because its money could never be recorded")
    void anAmountSmallerThanItsFeeIsNotStarted() throws Exception {
        UUID order = UUID.randomUUID();
        doAnswer(call -> new OrderClient.Taken(order, new BigDecimal("3.00"), "ZMW", Instant.now().plusSeconds(2_700))).when(orders)
                .takeProviderPayment(eq(order), any());

        MvcResult refused = mvc.perform(post("/api/v1/payments/provider-payments")
                .with(jwt().jwt(j -> j.subject("kc-" + UUID.randomUUID())).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order + "\",\"channel\":\"MOBILE_MONEY\",\"phone\":\"0961234567\"}")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(refused)).isEqualTo("amount-too-small-for-channel");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM provider_payment WHERE order_id = ?", order);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("NOT_SENT");
        assertThat(zynlePay.askedFor((String) row.get("reference_no"))).as("ZynlePay is asked for nothing").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'provider_payment' AND aggregate_id = ?", Long.class,
                row.get("id").toString())).as("the order is told to let the attempt go").isEqualTo(1);
    }

    @Test
    @DisplayName("ZynlePay reported K4.00 paid where K180.00 was asked: the owner cannot record K4.00 against a K5.10 fee; it is refused by name, not by a database error")
    void aReportedAmountSmallerThanItsFee() throws Exception {
        UUID id = attempt("PENDING", null, "180.00");
        String reference = jdbc.queryForObject("SELECT reference_no FROM provider_payment WHERE id = ?", String.class, id);
        zynlePay.statusOf(reference, new Paid("100", new BigDecimal("4.00"), "ZMW", null, null, null, "MTN"));
        checks.checkOne(id, Instant.now(), "STAFF", "user:kc-sara");

        MvcResult refused = resolve(id, "{\"outcome\":\"PAID\",\"evidence\":\"ZynlePay dashboard shows K4.00 settled\",\"gross\":4.00}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(refused)).isEqualTo("amount-too-small-for-channel");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE provider_payment_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_payment WHERE id = ?", String.class, id)).isEqualTo("UNRESOLVED");
    }

    @Test
    @DisplayName("ZynlePay says 'failed' between the owner's check and the owner's decision: 'paid' is not recorded on the older answer; the owner is told to look again")
    void anAnswerThatArrivesBetweenTheCheckAndTheDecision() throws Exception {
        UUID id = attempt("UNRESOLVED", "NO_ANSWER", "180.00");
        String reference = jdbc.queryForObject("SELECT reference_no FROM provider_payment WHERE id = ?", String.class, id);
        // The owner's own check hears "still deciding"; straight after it, the checker hears "failed".
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if ("RESOLVE".equals(call.getArgument(2))) {
                zynlePay.statusOf(reference, new Failed("995"));
                checks.checkOne(id, Instant.now(), "STAFF", "user:kc-sara");
            }
            return result;
        }).when(checks).checkOne(eq(id), any(), any(), any());

        MvcResult refused = resolve(id, "{\"outcome\":\"PAID\",\"evidence\":\"ZynlePay dashboard shows it settled\"}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        // Which refusal depends on which side of the owner's own read the newer answer lands; either way "paid" is not recorded on the older one.
        assertThat(problem(refused)).isIn("provider-payment-changed", "provider-says-failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE provider_payment_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM provider_payment WHERE id = ?", String.class, id)).isEqualTo("DECLINED");
    }
}
