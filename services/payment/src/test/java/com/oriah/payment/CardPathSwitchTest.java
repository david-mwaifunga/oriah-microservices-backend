package com.oriah.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.payment.client.OrderClient;
import com.oriah.payment.config.ReconcilerSchedule;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.service.ChargeSettlement;
import com.oriah.payment.service.OrderOwnership;
import com.oriah.payment.service.PaymentReconciler;
import com.oriah.payment.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The card path is off unless asked for (Phase 7): money reaches an order only by a person confirming it, so there is no second road to PAID.
 * {@code oriah.payment.card.enabled} (absent or false) removes the whole path (the intent endpoints, the service behind them, the fake card
 * gateway, the reconciler that settles stuck charges and the Feign client to order) and the service still starts, with the staff confirmation
 * side, the admin reads and the contact details untouched. Phase 13 turns it on when ZynlePay makes a card charge a confirmation.
 *
 * <p>Started with the reconciler ASKING to run (the shipped setting): its schedule must not be built without the card path it settles for, or
 * the service would not start at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false", "oriah.payment.card.enabled=false", "oriah.payment.reconciler.enabled=true"})
class CardPathSwitchTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor shopper() {
        return jwt().jwt(j -> j.subject("kc-alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    @DisplayName("the service starts with the card off even though the reconciler is asked to run, and nothing of the card path exists")
    void noCardBeans() {
        for (Class<?> card : new Class<?>[] {PaymentService.class, ChargeSettlement.class, PaymentReconciler.class, PaymentGateway.class,
                OrderOwnership.class, OrderClient.class, ReconcilerSchedule.class}) {
            assertThat(context.getBeanNamesForType(card)).as(card.getSimpleName()).isEmpty();
        }
    }

    @Test
    @DisplayName("the card endpoints are 404, every verb: a shopper cannot create or confirm a card charge")
    void cardEndpointsAreGone() throws Exception {
        String id = "00000000-0000-0000-0000-000000000001";

        mvc.perform(post("/api/v1/payments/intents").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + id + "\",\"amount\":100.00,\"currency\":\"ZMW\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments/intents/" + id + "/confirm").with(shopper()).header("Idempotency-Key", "k-1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/intents/" + id).with(shopper())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("what is not the card path stays: the contact details answer, and the admin reads answer for PAYMENT_ADMIN")
    void theRestStays() throws Exception {
        mvc.perform(get("/api/v1/payments/contact-info").with(shopper())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/payments").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN")))).andExpect(status().isOk());
    }
}
