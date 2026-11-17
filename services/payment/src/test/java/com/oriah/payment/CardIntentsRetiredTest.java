package com.oriah.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first card path is gone (Phase 13). It took a card number and answered at once, which fits neither a prompt on a phone nor ZynlePay's own
 * card page, and Oriah must never see a card number. Phase 7 had already switched it off; Phase 13 replaces it with provider payments and
 * deletes it: its doors, its beans, its tables, and the staff list and summary that read those tables.
 *
 * <p>What it wrote to the event log stays there: an event is a fact, and {@code payment.succeeded} / {@code payment.failed} are ignored by every
 * reader for good.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CardIntentsRetiredTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor shopper() {
        return jwt().jwt(j -> j.subject("kc-alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
        return jwt().jwt(j -> j.subject("kc-olive")).authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"),
                new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
    }

    @Test
    @DisplayName("the card intent doors are 404 for a shopper, every verb")
    void theIntentDoorsAreGone() throws Exception {
        String id = "00000000-0000-0000-0000-000000000001";

        mvc.perform(post("/api/v1/payments/intents").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + id + "\",\"amount\":100.00,\"currency\":\"ZMW\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments/intents/" + id + "/confirm").with(shopper()).header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cardNumber\":\"4242424242424242\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/intents/" + id).with(shopper())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the staff list and summary of card intents are 404: there are no intents to list")
    void theStaffListOfIntentsIsGone() throws Exception {
        mvc.perform(get("/api/v1/admin/payments").with(owner())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/payments/summary").with(owner())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("its tables are dropped, and nothing of it is left in the running service")
    void itsTablesAndBeansAreGone() {
        for (String table : new String[] {"payment_intents", "idempotency_keys", "fake_gateway_charge"}) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, "public." + table)).as(table).isTrue();
        }
        for (String bean : new String[] {"paymentService", "chargeSettlement", "paymentReconciler", "fakePaymentGateway", "orderOwnership",
                "reconcilerSchedule", "paymentController", "adminPaymentController"}) {
            assertThat(context.containsBean(bean)).as(bean).isFalse();
        }
    }

    @Test
    @DisplayName("what was never the card path stays: the contact details, staff confirmations, held money, refunds")
    void theRestStays() throws Exception {
        mvc.perform(get("/api/v1/payments/contact-info").with(shopper())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/payments/confirmations/pending").with(owner())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/payments/held-money").with(owner())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.payment_confirmation') IS NOT NULL", Boolean.class)).isTrue();
    }
}
