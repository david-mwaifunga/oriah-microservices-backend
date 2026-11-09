package com.oriah.payment.integration;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@code POST /api/v1/payments/intents} accepts, and what it must refuse.
 *
 * <h2>Why the DTO and not the database</h2>
 *
 * {@code payment_intents.amount} is {@code NUMERIC(12, 2) CHECK (amount > 0)},
 * so the column already refuses a few things. But a column is a backstop, not
 * a contract: reaching it means validation had a hole, and Postgres answers a
 * violated constraint with an exception that the shared advice reports as
 *
 * <pre>500 An unexpected error occurred. Use the traceId to correlate...</pre>
 *
 * Verified against the running stack: an amount of 100000000000.00 exceeds
 * {@code NUMERIC(12,2)} and comes back 500. That is wrong twice over -- the
 * caller is told nothing it can act on, and an ordinary bad request is
 * recorded as a server fault, which is the signal an operator pages on.
 *
 * <p>So the bounds move into the DTO, where they produce a 400 with
 * fieldErrors, and the CHECK constraint goes back to being a thing that never
 * fires. If it ever does fire again, a 500 is then the honest answer.
 *
 * <h2>Currency was not validated at all</h2>
 *
 * {@code currency} was {@code @Size(min = 3, max = 3)} and nothing else:
 * nullable, any case, any three letters. Three consequences, all verified live:
 *
 * <ul>
 *   <li>Omitted, it became {@code "USD"} -- a hardcoded default in a platform
 *       whose prices are in Kwacha, and duplicated in two places
 *       ({@code PaymentService.createIntent} and the {@code PaymentIntent}
 *       constructor), which is how two defaults drift apart.</li>
 *   <li>{@code "zmw"} was stored as-is. order-service compares the intent's
 *       currency to the order's with {@code equals}, so a lower-case intent
 *       cannot settle an upper-case order -- and {@code AdminPaymentSummary}
 *       groups captured totals BY currency, so "zmw" and "ZMW" split one
 *       figure into two rows that each look like the whole.</li>
 *   <li>{@code "XQZ"} -- not a currency -- was accepted.</li>
 * </ul>
 *
 * <p>The fix makes it required rather than defaulted. Nothing has to invent a
 * currency: checkout always sends the bag's, and every other construction site
 * passes one explicitly. A service that guesses which currency money is in is
 * worse than one that refuses to proceed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CreateIntentValidationTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    /**
     * For the refused requests only, which never create anything. A request
     * that is expected to create an intent uses a fresh order: an order may
     * have only one intent that is pending, being charged or paid (V5), so a
     * second success for this order would rightly be a 409.
     */
    private static final String ORDER = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentIntentRepository intents;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    private void refused(String body, String field) throws Exception {
        long before = intents.count();
        mvc.perform(post("/api/v1/payments/intents")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        org.hamcrest.Matchers.hasItem(field)));
        // A 400 that still wrote a row would be the worst of both answers.
        org.assertj.core.api.Assertions.assertThat(intents.count())
                .as("a refused intent must not be persisted")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("an amount beyond NUMERIC(12,2) is a 400, not a 500 from the column")
    void amountCeilingIsEnforcedBeforeTheDatabase() throws Exception {
        // 100,000,000,000.00 overflows the column. Live, this was a 500.
        refused("{\"orderId\":\"" + ORDER + "\",\"amount\":100000000000.00,"
                + "\"currency\":\"ZMW\"}", "amount");
    }

    @Test
    @DisplayName("the existing 0.01 floor still holds")
    void amountFloorStillHolds() throws Exception {
        // Regression guard: adding a ceiling must not disturb the floor, which
        // is the one constraint this endpoint already had.
        refused("{\"orderId\":\"" + ORDER + "\",\"amount\":0.001,"
                + "\"currency\":\"ZMW\"}", "amount");
    }

    @Test
    @DisplayName("a missing currency is refused rather than defaulted to USD")
    void missingCurrencyIsRefused() throws Exception {
        refused("{\"orderId\":\"" + ORDER + "\",\"amount\":100.00}", "currency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zmw", "Zmw", "zMW"})
    @DisplayName("a lower-case currency is refused, because equals() and GROUP BY both care")
    void lowerCaseCurrencyIsRefused(String currency) throws Exception {
        refused("{\"orderId\":\"" + ORDER + "\",\"amount\":100.00,"
                + "\"currency\":\"" + currency + "\"}", "currency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Z", "ZMWX", "12 ", "Z-W", "   "})
    @DisplayName("anything that is not three upper-case letters is refused")
    void malformedCurrencyIsRefused(String currency) throws Exception {
        refused("{\"orderId\":\"" + ORDER + "\",\"amount\":100.00,"
                + "\"currency\":\"" + currency + "\"}", "currency");
    }

    @Test
    @DisplayName("a well-formed intent is still created")
    void theHappyPathIsUntouched() throws Exception {
        // The bounds must narrow the input, not the feature. XQZ is not a real
        // currency and is deliberately still accepted: validating against the
        // ISO register would couple payment to a list that changes without us,
        // and the shape check is what stops the mismatches that actually bite.
        mvc.perform(post("/api/v1/payments/intents")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":4500.00,"
                                + "\"currency\":\"ZMW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.amount").value(4500.00))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("the largest amount the column can hold is accepted")
    void theCeilingIsInclusive() throws Exception {
        // The boundary itself, so the ceiling is pinned to the column's real
        // capacity rather than to a round number someone liked.
        mvc.perform(post("/api/v1/payments/intents")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":9999999999.99,"
                                + "\"currency\":\"ZMW\"}"))
                .andExpect(status().isCreated());
    }
}
