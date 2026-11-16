package com.oriah.notification.orders;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What notification remembers about an order between its events: whose it is, its receipt number once paid, and that it was paid. Each fact is
 * learnt once and never forgotten or changed, whatever order the events arrive in. No amount is kept.
 */
@ServiceTest
class OrderRefsTest {

    private static final Instant T1 = Instant.parse("2026-11-09T07:30:00Z");
    private static final Instant T2 = Instant.parse("2026-11-09T08:30:00Z");

    @Autowired
    private OrderRefs orderRefs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    @Test
    @DisplayName("an order nobody has heard of is not known")
    void anUnknownOrderIsNotKnown() {
        assertThat(orderRefs.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("remembers whose order it is")
    void remembersTheOwner() {
        UUID order = UUID.randomUUID();
        orderRefs.note(order, "kc-alice", false, null, null, null, T1);

        assertThat(orderRefs.find(order)).hasValueSatisfying(ref -> {
            assertThat(ref.orderId()).isEqualTo(order);
            assertThat(ref.keycloakId()).isEqualTo("kc-alice");
            assertThat(ref.phoneOrder()).isFalse();
            assertThat(ref.livePhoneSale()).isNull();
            assertThat(ref.receiptNumber()).isNull();
            assertThat(ref.paidAt()).isNull();
        });
    }

    @Test
    @DisplayName("keeps the receipt number and the moment it was paid once known")
    void keepsTheReceiptNumberOnceKnown() {
        UUID order = UUID.randomUUID();
        orderRefs.note(order, "kc-alice", false, null, null, null, T1);
        orderRefs.note(order, "kc-alice", false, null, "OR-000123", T1, T1);

        assertThat(orderRefs.find(order)).hasValueSatisfying(ref -> {
            assertThat(ref.receiptNumber()).isEqualTo("OR-000123");
            assertThat(ref.paidAt()).isEqualTo(T1);
        });
    }

    @Test
    @DisplayName("a later snapshot that says less forgets nothing, and one that says something else changes nothing")
    void aLaterSnapshotForgetsNothing() {
        UUID order = UUID.randomUUID();
        orderRefs.note(order, "kc-alice", false, null, "OR-000123", T1, T1);
        orderRefs.note(order, null, false, null, null, null, T2);
        orderRefs.note(order, "kc-somebody-else", false, null, "OR-999999", T2, T2);

        assertThat(orderRefs.find(order)).hasValueSatisfying(ref -> {
            assertThat(ref.keycloakId()).isEqualTo("kc-alice");
            assertThat(ref.receiptNumber()).isEqualTo("OR-000123");
            assertThat(ref.paidAt()).isEqualTo(T1);
        });
    }

    @Test
    @DisplayName("a phone order has no owner, and the kind of sale is kept as first said")
    void aPhoneOrderHasNoOwnerAndKeepsItsKind() {
        UUID order = UUID.randomUUID();
        orderRefs.note(order, null, true, null, null, null, T1);
        assertThat(orderRefs.find(order)).hasValueSatisfying(ref -> {
            assertThat(ref.phoneOrder()).isTrue();
            assertThat(ref.livePhoneSale()).isNull();
        });

        orderRefs.note(order, null, true, Boolean.TRUE, null, null, T2);
        orderRefs.note(order, null, true, Boolean.FALSE, null, null, T2);
        assertThat(orderRefs.find(order).orElseThrow().livePhoneSale()).isTrue();
    }

    @Test
    @DisplayName("the table refuses a phone order with an owner, and a kind of phone sale on a web order")
    void theTableHoldsTheRules() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO order_ref (order_id, keycloak_id, phone_order, updated_at) VALUES (?, 'kc-x', TRUE, now())",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("order_ref_owner_by_channel");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO order_ref (order_id, keycloak_id, live_phone_sale, updated_at) VALUES (?, 'kc-x', TRUE, now())",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("order_ref_live_only_for_phone");
    }

    @Test
    @DisplayName("keeps no amount: a total can change without an event, so none is remembered to be printed later")
    void keepsNoAmount() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_name = 'order_ref'
                   AND (data_type = 'numeric' OR column_name LIKE '%total%' OR column_name LIKE '%amount%' OR column_name LIKE '%currency%')""",
                String.class)).isEmpty();
    }
}
