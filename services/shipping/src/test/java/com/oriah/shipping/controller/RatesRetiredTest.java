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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The zone-and-country rate quote is gone (Phase 7). Delivery is priced per TOWN, as data the shop publishes, and quoted by
 * {@code POST /api/v1/shipping/quote}; checkout and order both use that. Nothing may answer {@code POST /api/v1/shipping/rates} any more: a client still
 * calling it must find out, not quietly be answered with a price nobody publishes.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(com.oriah.shipping.TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class RatesRetiredTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("POST /api/v1/shipping/rates is gone: 404, whatever the body")
    void theRatesEndpointIsGone() throws Exception {
        mvc.perform(post("/api/v1/shipping/rates")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationCountry\":\"ZM\",\"subtotal\":100.00,\"currency\":\"ZMW\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the quote that replaced it is there: a self-collect quote is K0 and needs no town")
    void theQuoteEndpointIsHere() throws Exception {
        mvc.perform(post("/api/v1/shipping/quote")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionCode\":\"SELF_COLLECT\",\"goodsTotal\":100.00,\"currency\":\"ZMW\"}"))
                .andExpect(status().isOk());
    }
}
