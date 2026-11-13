package com.oriah.shipping.controller;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.shipping.TestcontainersConfiguration;
import com.oriah.shipping.service.DeliveryOfferService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shipping now has a database, so it can be away. When it is, shipping cannot say what delivery costs, and the answer is 503
 * ({@code shipping-unavailable}): checkout and order read a 5xx from shipping as an outage and refuse the sale, never as "free delivery", and
 * never as a 500 shown to the shopper as a fault.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class DatabaseOutageTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeliveryOfferService offers;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor shopper() {
        return jwt().jwt(j -> j.subject("kc-alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    @DisplayName("a quote while the database is away is a 503 `shipping-unavailable`, not a 500 and not a free delivery")
    void aQuoteDuringAnOutage() throws Exception {
        when(offers.quote(any(), any(), any(), any(), any())).thenThrow(new CannotGetJdbcConnectionException("no connection", new java.sql.SQLException("down")));

        mvc.perform(post("/api/v1/shipping/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + UUID.randomUUID() + "\",\"optionCode\":\"YANGO\",\"goodsTotal\":" + new BigDecimal("100.00") + "}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/shipping-unavailable"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("what is offered while the database is away is a 503 too, whatever the failure was called: a lost connection or a timeout")
    void offersDuringAnOutage() throws Exception {
        when(offers.offers()).thenThrow(new DataAccessResourceFailureException("connection lost")).thenThrow(new QueryTimeoutException("too slow"));

        mvc.perform(get("/api/v1/shipping/towns").with(shopper())).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/shipping/towns").with(shopper())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/shipping-unavailable"));
    }
}
