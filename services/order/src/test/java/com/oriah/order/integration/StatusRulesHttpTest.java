package com.oriah.order.integration;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may move an order, and where (Phase 7). An order is a hold on pieces for a shopper who has not paid yet, and it becomes PAID only because money
 * arrived: a staff-confirmed payment settles it (the settlement saga), never a person asking for it. So a person may drive the fulfilment steps and cancel
 * an UNPAID order, nothing more; the shopper may cancel only an order still unpaid and unclaimed (CREATED); and a PAID sale is never undone by a status
 * change (that is a refund, Phase 12). Every refusal is a 409 that appends nothing to the event log, and an order that does not exist is a 404 before any
 * rule is looked at.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StatusRulesHttpTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN"));
    }

    private static RequestPostProcessor shopper(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** An order of one piece for {@code owner}, taken to {@code status} by the aggregate itself (the way the saga and the sweeper do). */
    private Order order(String owner, OrderStatus... path) {
        Order order = new Order(owner, null, "ZMW", "SELF_COLLECT", BigDecimal.ZERO);
        order.addItem(UUID.randomUUID(), "Aurelia ring", 1, new BigDecimal("100.00"));
        for (OrderStatus step : path) {
            order.transitionTo(step);
        }
        return orders.saveAndFlush(order);
    }

    private long events(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'order' AND aggregate_id = ?", Long.class, orderId.toString());
    }

    private org.springframework.test.web.servlet.ResultActions transition(UUID id, String target) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + id + "/transition").with(staff()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"" + target + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions cancelAs(String subject, UUID id) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(shopper(subject)));
    }

    // ---------------------------------------------------------------- the admin transition

    @Test
    @DisplayName("no person makes an order PAID, EXPIRED or PAYMENT_SUBMITTED: 409, nothing changes and nothing is announced")
    void adminCannotReachTheMoneyAndHoldStates() throws Exception {
        Order unpaid = order("kc-alice");
        long before = events(unpaid.id());

        for (String target : new String[] {"PAID", "EXPIRED", "PAYMENT_SUBMITTED"}) {
            transition(unpaid.id(), target).andExpect(status().isConflict());
        }

        assertThat(orders.findById(unpaid.id()).orElseThrow().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(events(unpaid.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("an order that does not exist is a 404 whatever is asked of it: the lookup comes before the rule")
    void unknownIsNotFoundBeforeTheRule() throws Exception {
        transition(UUID.randomUUID(), "PAID").andExpect(status().isNotFound());
        transition(UUID.randomUUID(), "FULFILLED").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the fulfilment steps still work for staff, in order: a PAID order is fulfilled, shipped and delivered")
    void fulfilmentStillWorks() throws Exception {
        Order paid = order("kc-alice", OrderStatus.PAID);

        transition(paid.id(), "FULFILLED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FULFILLED"));
        transition(paid.id(), "SHIPPED").andExpect(status().isOk());
        transition(paid.id(), "DELIVERED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
        transition(paid.id(), "CANCELLED").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("staff cancel an unpaid order, and cannot cancel a PAID one: a paid sale is not undone by a status change")
    void staffCancelOnlyWhatIsUnpaid() throws Exception {
        Order unpaid = order("kc-alice");
        Order paid = order("kc-alice", OrderStatus.PAID);

        transition(unpaid.id(), "CANCELLED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        long before = events(paid.id());
        transition(paid.id(), "CANCELLED").andExpect(status().isConflict());

        assertThat(orders.findById(paid.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(events(paid.id())).isEqualTo(before);
    }

    // ---------------------------------------------------------------- the shopper's cancel

    @Test
    @DisplayName("a shopper cancels their own order while it is CREATED")
    void shopperCancelsAnUnpaidOrder() throws Exception {
        Order mine = order("kc-alice");

        cancelAs("kc-alice", mine.id()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("a shopper cannot cancel a PAID order (409), nor one whose payment claim is submitted (409: money may be on its way), nor a lapsed one; nothing is announced")
    void shopperCannotCancelWhatIsPaidOrClaimed() throws Exception {
        Order paid = order("kc-alice", OrderStatus.PAID);
        Order claimed = order("kc-alice", OrderStatus.PAYMENT_SUBMITTED);
        Order lapsed = order("kc-alice", OrderStatus.EXPIRED);
        long before = events(paid.id()) + events(claimed.id()) + events(lapsed.id());

        cancelAs("kc-alice", paid.id()).andExpect(status().isConflict());
        cancelAs("kc-alice", claimed.id()).andExpect(status().isConflict());
        cancelAs("kc-alice", lapsed.id()).andExpect(status().isConflict());

        assertThat(orders.findById(paid.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.findById(claimed.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_SUBMITTED);
        assertThat(events(paid.id()) + events(claimed.id()) + events(lapsed.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("someone else's order is a 404 to a shopper, exactly as one that does not exist")
    void othersOrderIsNotFound() throws Exception {
        Order alices = order("kc-alice");

        cancelAs("kc-bob", alices.id()).andExpect(status().isNotFound());
        cancelAs("kc-bob", UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(orders.findById(alices.id()).orElseThrow().status()).isEqualTo(OrderStatus.CREATED);
    }
}
