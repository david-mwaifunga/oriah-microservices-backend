package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/admin/payments/summary} — exact totals for the admin
 * dashboard.
 *
 * <h2>Why this exists rather than summing a page in the browser</h2>
 *
 * The dashboard's revenue card needs a number. A page of payments carries only
 * the rows on that page, so summing it client-side produces a figure that is
 * right until there is more than one page and then silently under-reports
 * money — with no way for the reader to tell. A count is safe to take from
 * {@code totalElements} because that is a COUNT query; a SUM is not, so it gets
 * an endpoint that does the aggregation in the database.
 *
 * <h2>Totals are per currency, never summed across them</h2>
 *
 * Adding 100 USD to 100 EUR produces 200 of nothing. Every row carries its own
 * currency, so captured totals come back as a list keyed by currency and the UI
 * renders what is there. Today every row is USD and the list has one entry;
 * the shape is chosen so that stops being true without anyone noticing a
 * meaningless number on a dashboard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class AdminPaymentSummaryTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ROLE_PAYMENT_ADMIN =
            new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN");

    private static final String PATH = "/api/v1/admin/payments/summary";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository repository;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    private PaymentIntent intent(String amount, String currency) {
        return new PaymentIntent(UUID.randomUUID(), "kc-" + UUID.randomUUID(),
                new BigDecimal(amount), currency);
    }

    private void captured(String amount, String currency) {
        PaymentIntent i = intent(amount, currency);
        i.markSucceeded();
        repository.saveAndFlush(i);
    }

    private void failed(String amount) {
        PaymentIntent i = intent(amount, "USD");
        i.markFailed("card declined");
        repository.saveAndFlush(i);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a plain USER is refused by the service, not only by the gateway")
    void userIsForbidden() throws Exception {
        mvc.perform(get(PATH).with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("captured totals are summed in the database, not on a page")
    void capturedTotalIsExact() throws Exception {
        captured("100.00", "USD");
        captured("250.50", "USD");
        failed("999.00");          // must not count toward captured
        repository.saveAndFlush(intent("77.00", "USD"));  // PENDING

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.captured[0].currency").value("USD"))
                .andExpect(jsonPath("$.captured[0].amount").value(350.50));
    }

    @Test
    @DisplayName("currencies are kept apart instead of being added together")
    void currenciesAreNotMixed() throws Exception {
        // 100 USD + 100 EUR is not 200 of anything. If this ever collapses to
        // a single figure, the dashboard starts reporting a number that means
        // nothing, and nobody can tell from looking at it.
        captured("100.00", "USD");
        captured("40.00", "EUR");

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedCount").value(2))
                .andExpect(jsonPath("$.captured.length()").value(2))
                .andExpect(jsonPath("$.captured[?(@.currency == 'USD')].amount")
                        .value(org.hamcrest.Matchers.hasItem(100.00)))
                .andExpect(jsonPath("$.captured[?(@.currency == 'EUR')].amount")
                        .value(org.hamcrest.Matchers.hasItem(40.00)));
    }

    @Test
    @DisplayName("soft-deleted rows are excluded from every figure")
    void softDeletedExcluded() throws Exception {
        captured("100.00", "USD");
        PaymentIntent gone = intent("5000.00", "USD");
        gone.markSucceeded();
        // markDeleted(), not repository.delete(): the latter hard-deletes, so
        // it would prove nothing about the aggregate's filter.
        gone.markDeleted();
        repository.saveAndFlush(gone);

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedCount").value(1))
                .andExpect(jsonPath("$.captured[0].amount").value(100.00));
    }

    @Test
    @DisplayName("an empty ledger reports zeroes and an empty list, not nulls")
    void emptyLedger() throws Exception {
        // A dashboard that renders "null" because nothing has been sold yet is
        // a worse first impression than one that renders 0.
        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.pendingCount").value(0))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.captured").isArray())
                .andExpect(jsonPath("$.captured.length()").value(0));
    }
}
