package com.oriah.finance.events;

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

import com.oriah.finance.TestcontainersConfiguration;
import com.oriah.finance.events.Events.Paid;
import com.oriah.finance.events.Events.Sold;
import com.oriah.finance.events.RefundEvents.Corrected;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The check of finance against payment (Phase 12): a correction that owes a customer money should have a debt in payment. If payment never told finance of one (its
 * copy of the correction was refused, say), the report says so and says the month is not to be relied on yet. The wait before it says so is a setting
 * ({@code oriah.finance.refund-debt-grace}, a day by default: payment hears of the correction a moment after finance does); here it is nothing, so the check is seen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false", "oriah.finance.refund-debt-grace=PT0S"})
class RefundStatusTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SaleApplier sales;
    @Autowired
    private CorrectionApplier corrections;
    @Autowired
    private RefundDueApplier dues;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private JsonNode status(String month) throws Exception {
        String body = mvc.perform(get("/api/v1/admin/finance/reports/profit/" + month)
                .with(jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN")))).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("status");
    }

    @Test
    @DisplayName("a refund owed with no debt in payment is counted, and the month says not to rely on it yet; once payment's debt arrives it is counted no more")
    void anOwedRefundWithNoDebtIsFlagged() throws Exception {
        int before = status("2017-09").get("refundsOwedWithoutDebt").asInt();
        Paid paid = new Paid().line(Sold.of(UUID.randomUUID(), 1, "1000.00", "0.00")).arrived("2017-09-03T08:00:00Z");
        sales.apply(paid.context(), paid.body());
        Corrected c = Corrected.cancelling(paid).at("2017-09-05T09:15:00Z");
        corrections.apply(c.context(), c.body());

        JsonNode waiting = status("2017-09");
        assertThat(waiting.get("refundsOwedWithoutDebt").asInt()).isEqualTo(before + 1);
        assertThat(waiting.get("doNotRelyYet").asBoolean()).isTrue();

        dues.apply(RefundEvents.dueContext(c.id, c.occurredAt), RefundEvents.due(c.id, c.order, c.confirmation, "1000.00", "OWED", c.occurredAt));

        assertThat(status("2017-09").get("refundsOwedWithoutDebt").asInt()).isEqualTo(before);
    }
}
