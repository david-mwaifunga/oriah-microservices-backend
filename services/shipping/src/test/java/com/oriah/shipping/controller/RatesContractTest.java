package com.oriah.shipping.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/shipping/rates} as checkout -- and the storefront through
 * it -- sees it (MVP_PLAN Phase 0, items 1 and 2).
 *
 * <p>A country outside Zambia is a 422 with its own problem type: the request
 * is well-formed, but it is something Oriah does not do, and the shopper can
 * act on it (choose a Zambian address). Checkout recognises the type and
 * passes it on; a generic 500 here would read as "the shop is broken".
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class RatesContractTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private ResultActions rates(String body) throws Exception {
        return mvc.perform(post("/api/v1/shipping/rates")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("outside Zambia: 422, outside-delivery-area, in words the shopper can act on")
    void outsideZambiaIsUnprocessable() throws Exception {
        rates("{\"destinationCountry\":\"US\",\"subtotal\":100.00,\"currency\":\"ZMW\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/outside-delivery-area"))
                .andExpect(jsonPath("$.detail").value("We deliver within Zambia only."));
    }

    @Test
    @DisplayName("Zambia: one option, SELF_COLLECT at no charge; zones are gone from the contract")
    void aZambianQuoteIsSelfCollectAtNoCharge() throws Exception {
        rates("{\"destinationCountry\":\"ZM\",\"subtotal\":100.00,\"currency\":\"ZMW\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.length()").value(1))
                .andExpect(jsonPath("$.options[0].code").value("SELF_COLLECT"))
                .andExpect(jsonPath("$.options[0].cost").value(0.0))
                .andExpect(jsonPath("$.options[0].currency").value("ZMW"))
                .andExpect(jsonPath("$.zone").doesNotExist());
    }

    @Test
    @DisplayName("a currency the card is not written in is still a 400")
    void aForeignCurrencyIsStillABadRequest() throws Exception {
        rates("{\"destinationCountry\":\"ZM\",\"subtotal\":100.00,\"currency\":\"USD\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/bad-request"));
    }

    @Test
    @DisplayName("naming an option that is not offered gives an empty list, not an error")
    void aNamedOptionThatIsNotOfferedIsAnEmptyList() throws Exception {
        rates("{\"destinationCountry\":\"ZM\",\"subtotal\":100.00,\"currency\":\"ZMW\","
                + "\"deliveryOption\":\"OWN_DELIVERY\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.length()").value(0));
    }
}
