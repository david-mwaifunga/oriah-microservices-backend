package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.dto.ConfirmPaymentRequest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Money that came in and could not pay a sale is OWED to the customer, never revenue (Phase 7): the owner sees it here, with what it was and why it is
 * held. PAYMENT_ADMIN only (held by the owner): the staff who confirm see their own confirmation's outcome, not this list.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class HeldMoneyHttpTest {

    private static final String HELD = "/api/v1/admin/payments/held-money";
    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private SettlementOutcomes outcomes;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor owner() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"));
    }

    private UUID heldConfirmation(UUID order, String gross, String reason) {
        UUID id = confirmations.confirm(new ConfirmPaymentRequest(order, "MTN", "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), new BigDecimal(gross),
                new BigDecimal("10.00"), null, null, null, null), "key-" + UUID.randomUUID(), SAM).view().id();
        outcomes.unsettled(id, reason);
        return id;
    }

    @Test
    @DisplayName("the owner sees what is owed: the confirmation it came from, the order, why it is held and how much, newest first; the staff who confirm, and shoppers, do not")
    void theOwnerSeesWhatIsOwed() throws Exception {
        UUID order = UUID.randomUUID();
        UUID id = heldConfirmation(order, "1000.00", "ORDER_CANCELLED");

        mvc.perform(get(HELD).with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.confirmationId=='" + id + "')].orderId").value(order.toString()))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + id + "')].reason").value("ORDER_CANCELLED"))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + id + "')].owedGross").value(1000.00))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + id + "')].currency").value("ZMW"))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + id + "')].status").value("OWED"))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + id + "')].method").value("MTN"))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + id + "')].confirmedBy").value("user:kc-sam"));

        mvc.perform(get(HELD)).andExpect(status().isUnauthorized());
        mvc.perform(get(HELD).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")))).andExpect(status().isForbidden());
        mvc.perform(get(HELD).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a voided confirmation's money is no longer owed: it leaves the owed list and shows under VOIDED; ALL shows both, and an unknown status is 400")
    void statuses() throws Exception {
        UUID owed = heldConfirmation(UUID.randomUUID(), "300.00", "WRONG_AMOUNT");
        UUID voided = heldConfirmation(UUID.randomUUID(), "400.00", "ORDER_UNKNOWN");
        confirmations.voidConfirmation(voided, "keyed against the wrong order", SAM);

        mvc.perform(get(HELD).with(owner()))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + owed + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.confirmationId=='" + voided + "')]").isEmpty());
        mvc.perform(get(HELD).param("status", "VOIDED").with(owner()))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + voided + "')].status").value("VOIDED"))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + owed + "')]").isEmpty());
        mvc.perform(get(HELD).param("status", "ALL").with(owner()))
                .andExpect(jsonPath("$[?(@.confirmationId=='" + voided + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.confirmationId=='" + owed + "')]").isNotEmpty());
        // REPAID is a status since Phase 12 (held money can be paid back); a status nobody knows is still refused.
        mvc.perform(get(HELD).param("status", "REPAID").with(owner())).andExpect(status().isOk());
        mvc.perform(get(HELD).param("status", "SPENT").with(owner())).andExpect(status().isBadRequest());
    }
}
