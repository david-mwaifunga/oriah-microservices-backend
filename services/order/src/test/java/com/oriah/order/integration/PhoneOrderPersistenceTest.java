package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.order.model.Channel;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.model.RecordedSale;
import com.oriah.order.model.SaleKind;
import com.oriah.order.service.OrderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An order staff recorded for a caller with no account, stored and read back (Phase 9, slice 9.5): it saves with no owner, keeps what staff typed exactly,
 * and belongs to no shopper, so every shopper's door answers "not found" for it (the same answer as for an id that does not exist). Real Postgres.
 */
class PhoneOrderPersistenceTest extends SaleTestBase {

    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired
    private OrderService orders;
    @Autowired
    private TransactionTemplate tx;

    private UUID savedPhoneOrder() {
        RecordedSale sale = new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", SaleKind.LATE_PAPER, Instant.now().minusSeconds(86400 * 2),
                "the paper note was in the drawer", "P-17", "a".repeat(64), new BigDecimal("60.00"), "a regular customer");
        Order order = Order.recorded(sale, "ZMW", "OWN_DELIVERY", new BigDecimal("60.00"), TOWN, "ORIAH_COLLECTS");
        order.addItem(RING, "Aurelia 18k / Ring Size M", 1, new BigDecimal("4500.00"));
        order.recordDelivery("Mulenga Chanda", "+260971234567", "Plot 12 Great East Road", null, "Lusaka", null, null, "ZM", "Oriah delivery", "Lusaka");
        return repository.saveAndFlush(order).id();
    }

    @Test
    @DisplayName("a phone order saves with no account, and the row keeps the caller, the recorder, the kind, the dates, the slip and the agreed delivery exactly")
    void aPhoneOrderSavesWithNoAccount() {
        UUID id = savedPhoneOrder();

        var row = row(id);
        assertThat(row.get("keycloak_id")).isNull();
        assertThat(row.get("channel")).isEqualTo("PHONE");
        assertThat(row.get("recorded_by")).isEqualTo("user:kc-sam");
        assertThat(row.get("caller_name")).isEqualTo("Mulenga Chanda");
        assertThat(row.get("caller_phone")).isEqualTo("+260971234567");
        assertThat(row.get("sale_kind")).isEqualTo("LATE_PAPER");
        assertThat(row.get("backdate_reason")).isEqualTo("the paper note was in the drawer");
        assertThat(row.get("slip_reference")).isEqualTo("P-17");
        assertThat(row.get("delivery_price_source")).isEqualTo("AGREED");
        assertThat((BigDecimal) row.get("delivery_agreed_amount")).isEqualByComparingTo("60.00");
        assertThat(row.get("delivery_agreed_reason")).isEqualTo("a regular customer");
        assertThat(at(row.get("occurred_at"))).isBefore(at(row.get("placed_at")).minusSeconds(86400));
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("4560.00");
        Order read = repository.findById(id).orElseThrow();
        assertThat(read.channel()).isEqualTo(Channel.PHONE);
        assertThat(read.keycloakId()).isNull();
        assertThat(read.occurredAt()).isEqualTo(at(row.get("occurred_at")));
    }

    @Test
    @DisplayName("a web order saved the usual way is a WEB order dated to when it was placed")
    void aWebOrderIsStillAWebOrder() throws Exception {
        UUID id = heldOrder();

        var row = row(id);
        assertThat(row.get("channel")).isEqualTo("WEB");
        assertThat(row.get("keycloak_id")).isEqualTo("kc-alice");
        assertThat(row.get("occurred_at")).isEqualTo(row.get("placed_at"));
        assertThat(row.get("delivery_price_source")).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("what the entity wrote at recording is never rewritten by a later save (the caller, the agreed delivery and the dates are facts)")
    void theRecordedFactsAreNeverRewritten() {
        UUID id = savedPhoneOrder();

        tx.executeWithoutResult(s -> {
            Order order = repository.findById(id).orElseThrow();
            order.transitionTo(OrderStatus.CANCELLED);
            repository.saveAndFlush(order);
        });

        var row = row(id);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("caller_name")).isEqualTo("Mulenga Chanda");
        assertThat((BigDecimal) row.get("delivery_agreed_amount")).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("a shopper cannot read, cancel or claim a phone order: each is 404, the same answer as for an order that does not exist, never a server error")
    void aShopperCannotReachAPhoneOrder() throws Exception {
        UUID phone = savedPhoneOrder();
        UUID missing = UUID.randomUUID();

        var read = mvc.perform(get("/api/v1/orders/" + phone).with(alice())).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var readMissing = mvc.perform(get("/api/v1/orders/" + missing).with(alice())).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/orders/" + phone + "/cancel").with(alice())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + phone + "/payment-claim").with(alice()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"MTN\",\"reference\":\"MP261113\"}")).andExpect(status().isNotFound());

        assertThat(read.replace(phone.toString(), "X").replaceAll("\"traceId\":\"[0-9a-f]+\"", "")).as("the same body as for a missing order")
                .isEqualTo(readMissing.replace(missing.toString(), "X").replaceAll("\"traceId\":\"[0-9a-f]+\"", ""));
        assertThat(orders.cancel("kc-alice", phone, "user:kc-alice")).isEmpty();
        assertThat(orders.cancel(null, phone, "user:nobody")).isEmpty();
        assertThat(repository.findById(phone).orElseThrow().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("a shopper's list of their own orders never includes a phone order")
    void aShoppersListHasNoPhoneOrder() throws Exception {
        savedPhoneOrder();
        UUID mine = heldOrder();

        var page = json.readTree(mvc.perform(get("/api/v1/orders/me").with(alice())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("content").get(0).get("id").asText()).isEqualTo(mine.toString());
    }

    @Test
    @DisplayName("cancelling a phone order that delivers forgets the recipient and the address (the status reaches the row first), and keeps the caller")
    void cancellingAPhoneOrderKeepsTheCaller() {
        UUID id = savedPhoneOrder();
        // Held and cancellable: the move helper is the staff path (ORDER_ADMIN's transition), which phone orders share.
        jdbc.update("UPDATE orders SET hold_state = 'HELD' WHERE id = ?", id);

        orders.transition(id, OrderStatus.CANCELLED, "user:kc-maya").orElseThrow();

        var row = row(id);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("recipient_name")).isNull();
        assertThat(row.get("address_line1")).isNull();
        assertThat(row.get("caller_name")).isEqualTo("Mulenga Chanda");
        assertThat(row.get("caller_phone")).isEqualTo("+260971234567");
    }
}
