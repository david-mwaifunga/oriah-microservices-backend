package com.oriah.order.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An order staff record for a caller with no account (Phase 9), as the aggregate sees it: it has no owner and says so, a web order cannot be built without
 * one, and every rule the database holds about a phone order (V13) is refused here first, in plain words, before a SQL error could be.
 */
class PhoneOrderTest {

    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static RecordedSale live() {
        return new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", SaleKind.LIVE_PHONE, Instant.now(), null, null, "a".repeat(64), null, null);
    }

    private static RecordedSale late(String reason) {
        return new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", SaleKind.LATE_PAPER, Instant.now().minusSeconds(86400 * 2), reason, "P-17", "b".repeat(64),
                null, null);
    }

    private static RecordedSale agreed(String amount, String reason) {
        return new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", SaleKind.LIVE_PHONE, Instant.now(), null, null, "c".repeat(64),
                amount == null ? null : new BigDecimal(amount), reason);
    }

    @Test
    @DisplayName("a web order cannot be built without its shopper: no owner, or a blank one, is refused")
    void aWebOrderNeedsItsOwner() {
        for (String owner : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> new Order(owner, null, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("owner");
        }
    }

    @Test
    @DisplayName("a recorded order has no owner, says it was recorded by staff for a caller, and is dated to when the sale happened")
    void aRecordedOrderHasNoOwner() {
        Order order = Order.recorded(live(), "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null);

        assertThat(order.keycloakId()).isNull();
        assertThat(order.channel()).isEqualTo(Channel.PHONE);
        assertThat(order.recordedBy()).isEqualTo("user:kc-sam");
        assertThat(order.callerName()).isEqualTo("Mulenga Chanda");
        assertThat(order.callerPhone()).isEqualTo("+260971234567");
        assertThat(order.saleKind()).isEqualTo(SaleKind.LIVE_PHONE);
        assertThat(order.occurredAt()).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.deliveryPriceSource()).isEqualTo("PUBLISHED");
        assertThat(order.deliveryAgreedAmount()).isNull();
        assertThat(order.totalsAuthoritative()).isTrue();
    }

    @Test
    @DisplayName("nobody owns a recorded order: not a shopper, not a missing subject, not an empty one; a web order is owned by its shopper only")
    void ownership() {
        Order phone = Order.recorded(live(), "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null);
        Order web = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"));

        assertThat(phone.isOwnedBy("kc-alice")).isFalse();
        assertThat(phone.isOwnedBy(null)).isFalse();
        assertThat(phone.isOwnedBy("")).isFalse();
        assertThat(web.isOwnedBy("kc-alice")).isTrue();
        assertThat(web.isOwnedBy("kc-bob")).isFalse();
        assertThat(web.isOwnedBy(null)).isFalse();
        assertThat(web.channel()).isEqualTo(Channel.WEB);
    }

    @Test
    @DisplayName("a late paper sale carries its reason, trimmed; without one it is refused; a live sale carries none")
    void aLateSaleHasItsReason() {
        Order order = Order.recorded(late("  the paper note was in the drawer "), "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null);

        assertThat(order.backdateReason()).isEqualTo("the paper note was in the drawer");
        assertThat(order.slipReference()).isEqualTo("P-17");
        assertThatThrownBy(() -> Order.recorded(late(null), "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.recorded(late("four"), "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null)).isInstanceOf(IllegalArgumentException.class);
        RecordedSale liveWithReason = new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", SaleKind.LIVE_PHONE, Instant.now(), "no reason belongs here", null,
                "a".repeat(64), null, null);
        assertThatThrownBy(() -> Order.recorded(liveWithReason, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a sale cannot have happened more than five minutes after it is recorded")
    void aSaleIsNotInTheFuture() {
        RecordedSale future = new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", SaleKind.LIVE_PHONE, Instant.now().plusSeconds(3600), null, null, "a".repeat(64), null, null);

        assertThatThrownBy(() -> Order.recorded(future, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    @DisplayName("a recorder and a caller's name are required; the request hash is 64 hex characters; a phone, when given, is +260 and digits")
    void aRecordedOrderIsWhole() {
        for (RecordedSale bad : new RecordedSale[] {
                new RecordedSale(null, "Mulenga Chanda", "+260971234567", SaleKind.LIVE_PHONE, Instant.now(), null, null, "a".repeat(64), null, null),
                new RecordedSale("user:kc-sam", " ", "+260971234567", SaleKind.LIVE_PHONE, Instant.now(), null, null, "a".repeat(64), null, null),
                new RecordedSale("user:kc-sam", "Mulenga Chanda", "0971234567", SaleKind.LIVE_PHONE, Instant.now(), null, null, "a".repeat(64), null, null),
                new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", SaleKind.LIVE_PHONE, Instant.now(), null, null, "not a hash", null, null),
                new RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", null, Instant.now(), null, null, "a".repeat(64), null, null)}) {
            assertThatThrownBy(() -> Order.recorded(bad, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a caller's phone may be missing only for a pickup")
    void thePhoneMayBeMissingOnlyForAPickup() {
        RecordedSale noPhone = new RecordedSale("user:kc-sam", "Mulenga Chanda", null, SaleKind.LIVE_PHONE, Instant.now(), null, null, "a".repeat(64), null, null);

        assertThat(Order.recorded(noPhone, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null).callerPhone()).isNull();
        assertThatThrownBy(() -> Order.recorded(noPhone, "ZMW", "OWN_DELIVERY", new BigDecimal("60.00"), TOWN, "ORIAH_COLLECTS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an agreed delivery is the order's delivery charge, kept with its reason, and the order's total moves by exactly it")
    void anAgreedDeliveryIsTheCharge() {
        Order order = Order.recorded(agreed("60.00", "a regular customer who lives nearby"), "ZMW", "OWN_DELIVERY", new BigDecimal("60.00"), TOWN, "ORIAH_COLLECTS");

        assertThat(order.deliveryPriceSource()).isEqualTo("AGREED");
        assertThat(order.deliveryAgreedAmount()).isEqualByComparingTo("60.00");
        assertThat(order.deliveryAgreedReason()).isEqualTo("a regular customer who lives nearby");
        assertThat(order.shippingAmount()).isEqualByComparingTo("60.00");
        assertThat(order.total()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("an agreed delivery is refused when it does not fit: a pickup, no town, an amount that is not the order's charge, a recipient-paid own delivery")
    void anAgreedDeliveryMustFit() {
        assertThatThrownBy(() -> Order.recorded(agreed("60.00", null), "ZMW", "SELF_COLLECT", new BigDecimal("0.00"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.recorded(agreed("60.00", null), "ZMW", "OWN_DELIVERY", new BigDecimal("60.00"), null, "ORIAH_COLLECTS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.recorded(agreed("60.00", null), "ZMW", "OWN_DELIVERY", new BigDecimal("70.00"), TOWN, "ORIAH_COLLECTS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.recorded(agreed("0.00", null), "ZMW", "YANGO", new BigDecimal("0.00"), TOWN, "ORIAH_COLLECTS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.recorded(agreed("0.00", null), "ZMW", "OWN_DELIVERY", new BigDecimal("0.00"), TOWN, "RECIPIENT_PAYS")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Order.recorded(agreed("0.00", null), "ZMW", "YANGO", new BigDecimal("0.00"), TOWN, "RECIPIENT_PAYS").deliveryAgreedAmount()).isEqualByComparingTo("0.00");
    }
}
