package com.oriah.notification.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What notification remembers about an order between its events ({@code order_ref}): whose it is, whether staff recorded it for a caller and
 * which kind of phone sale that was, its receipt number once paid, and that it was paid.
 *
 * <p><b>Each fact is learnt once and never changed or forgotten.</b> Events are facts and may arrive in any order; a later one that says less
 * (a snapshot with no receipt number) takes nothing away, and one that says something else changes nothing. That is one statement: an insert
 * that, when the order is already known, keeps every value it already has.
 *
 * <p>No amount is kept. A total can change without an event (a delivery waiver), so no message prints a remembered one.
 *
 * <p>It runs in the caller's transaction: the consumer notes an order in the same transaction as the Inbox's "applied" marker.
 */
@Component
public class OrderRefs {

    /**
     * @param keycloakId    the customer who owns a web order; null for a phone order
     * @param livePhoneSale for a phone order: true when it was taken live, false when typed in later from paper, null when no event has said
     * @param receiptNumber the shop's receipt number once the order is paid; null before, and for an order paid before receipt numbers existed
     * @param paidAt        when the order's payment was recorded by order, as first heard; null while unpaid as far as notification knows
     */
    public record OrderRef(UUID orderId, String keycloakId, boolean phoneOrder, Boolean livePhoneSale, String receiptNumber, Instant paidAt) {
    }

    private final JdbcTemplate jdbc;

    public OrderRefs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Notes what one event says about its order. A null means "this event does not say".
     *
     * @param now the service's clock
     */
    public void note(UUID orderId, String keycloakId, boolean phoneOrder, Boolean livePhoneSale, String receiptNumber, Instant paidAt,
                     Instant now) {
        jdbc.update("""
                INSERT INTO order_ref (order_id, keycloak_id, phone_order, live_phone_sale, receipt_number, paid_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE
                   SET keycloak_id     = COALESCE(order_ref.keycloak_id, EXCLUDED.keycloak_id),
                       live_phone_sale = COALESCE(order_ref.live_phone_sale, EXCLUDED.live_phone_sale),
                       receipt_number  = COALESCE(order_ref.receipt_number, EXCLUDED.receipt_number),
                       paid_at         = COALESCE(order_ref.paid_at, EXCLUDED.paid_at),
                       updated_at      = EXCLUDED.updated_at""",
                orderId, keycloakId, phoneOrder, livePhoneSale, blankToNull(receiptNumber), paidAt == null ? null : Timestamp.from(paidAt),
                Timestamp.from(now));
    }

    /** A PAID order was cancelled before it left (Phase 12). Kept from the first time it is heard; a repeat changes nothing. */
    public void cancelled(UUID orderId, Instant at, Instant now) {
        jdbc.update("UPDATE order_ref SET cancelled_at = COALESCE(cancelled_at, ?), updated_at = ? WHERE order_id = ?", Timestamp.from(at), Timestamp.from(now), orderId);
    }

    public Optional<OrderRef> find(UUID orderId) {
        List<OrderRef> found = jdbc.query("""
                SELECT order_id, keycloak_id, phone_order, live_phone_sale, receipt_number, paid_at FROM order_ref WHERE order_id = ?""",
                (row, n) -> new OrderRef(row.getObject("order_id", UUID.class), row.getString("keycloak_id"), row.getBoolean("phone_order"),
                        (Boolean) row.getObject("live_phone_sale"), row.getString("receipt_number"),
                        row.getTimestamp("paid_at") == null ? null : row.getTimestamp("paid_at").toInstant()),
                orderId);
        return found.stream().findFirst();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
