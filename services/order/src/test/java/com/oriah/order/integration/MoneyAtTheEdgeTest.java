package com.oriah.order.integration;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.money.MoneyEdgeGuard;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.model.Order;
import com.oriah.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A price on an order line is a whole number of ngwee (MVP_PLAN Phase 0, item 7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class MoneyAtTheEdgeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository orders;

    @MockitoBean
    private CatalogPricingClient catalog;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        orders.deleteAll();
    }

    @Test
    @DisplayName("every money field in order's requests carries the ngwee rule, lines included")
    void everyMoneyFieldIsGuarded() {
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.order.dto")).isPositive();
        assertThat(MoneyEdgeGuard.unguardedMoneyFields("com.oriah.order.dto")).isEmpty();
    }

    @Test
    @DisplayName("a line priced at a fraction of a ngwee is a 400 on that line, before catalog is asked")
    void aLinePriceFinerThanANgweeIsRefused() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("kc-alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + UUID.randomUUID() + "\",\"currency\":\"ZMW\","
                                + "\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\"" + com.oriah.order.QuoteFixtures.ANY_DIGEST + "\","
                                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\",\"title\":\"Aurelia\","
                                + "\"quantity\":1,\"unitPrice\":100.005}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("items[0].unitPrice")));
        assertThat(orders.count()).isZero();
        verifyNoInteractions(catalog);
    }

    @Test
    @DisplayName("an order line cannot be added at a fraction of a ngwee")
    void theAggregateRefusesItToo() {
        Order order = new Order("kc-alice", UUID.randomUUID(), "ZMW", "SELF_COLLECT", java.math.BigDecimal.ZERO);
        assertThatThrownBy(() -> order.addItem(UUID.randomUUID(), "Aurelia", 1, new BigDecimal("100.005")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100.005");
        assertThat(order.items()).isEmpty();
    }
}