package com.oriah.order.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.quote.DeliveryContact;

import lombok.extern.slf4j.Slf4j;

/**
 * Correcting who a PAID parcel is for and where it goes, before it is packed (Phase 8). The whole delivery block is replaced (nothing the caller leaves out
 * is kept), judged by the rules placement uses ({@link DeliveryContact} and a whole address), under the order's lock, and the previous details go to an
 * append-only history with who changed them and why. The option and the town the price was for are not the person's to change here (a different option or
 * town is a different price: cancel and place again). Nothing is published and nothing personal is logged.
 */
@Service
@Slf4j
public class DeliveryCorrectionService {

    /** The new delivery block: the recipient as typed, and the address lines. */
    public record Details(String recipientName, String recipientPhone, String line1, String line2, String city, String state, String postalCode, String country) {
    }

    private final OrderLocks locks;
    private final JdbcTemplate jdbc;
    private final CorrectionGuard corrections;

    public DeliveryCorrectionService(OrderLocks locks, JdbcTemplate jdbc, CorrectionGuard corrections) {
        this.locks = locks;
        this.jdbc = jdbc;
        this.corrections = corrections;
    }

    /**
     * @throws ResourceNotFoundException 404, no such order
     * @throws SalesProblem 409 {@code order-not-paid}, {@code no-delivery-details} (a pickup), {@code delivery-details-locked} (packed already)
     * @throws IllegalArgumentException (400) an unreadable name or phone
     */
    @Transactional
    public void correct(UUID orderId, Details details, String reason, String actor) {
        Order order = locks.lock(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        if (order.status() != OrderStatus.PAID && !isPackedOrLater(order.status())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-paid", "this order is " + order.status() + ", not paid: cancel it and place it again with the right details");
        }
        // A cancellation that is with inventory forgets these details the moment it finishes: they are not corrected under it (Phase 12).
        corrections.requireNoneInFlight(orderId);
        if (order.isSelfCollect()) {
            throw new SalesProblem(HttpStatus.CONFLICT, "no-delivery-details", "this order is collected by the shopper: nobody is delivered to");
        }
        if (isPackedOrLater(order.status())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "delivery-details-locked",
                    "this order is already " + order.status() + ": the parcel is labelled for where it was going, so the details can no longer be corrected");
        }
        String name = DeliveryContact.name(details.recipientName());
        String phone = DeliveryContact.phone(details.recipientPhone());
        int seq = jdbc.queryForObject("SELECT coalesce(max(seq), 0) + 1 FROM order_delivery_correction WHERE order_id = ?", Integer.class, orderId);
        jdbc.update("""
                INSERT INTO order_delivery_correction (id, order_id, seq, previous_recipient_name, previous_recipient_phone, previous_address_line1,
                    previous_address_line2, previous_address_city, previous_address_state, previous_address_postal_code, previous_address_country, reason,
                    corrected_by, corrected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), orderId, seq, order.recipientName(), order.recipientPhone(), order.addressLine1(),
                order.addressLine2(), order.addressCity(), order.addressState(), order.addressPostalCode(), order.addressCountry(), reason, actor,
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        jdbc.update("""
                UPDATE orders SET recipient_name = ?, recipient_phone = ?, address_line1 = ?, address_line2 = ?, address_city = ?, address_state = ?,
                    address_postal_code = ?, address_country = ?
                 WHERE id = ?""", name, phone, details.line1(), blankToNull(details.line2()), details.city(), blankToNull(details.state()),
                blankToNull(details.postalCode()), details.country().toUpperCase(java.util.Locale.ROOT), orderId);
        log.info("Order {} delivery details corrected (correction {}) by {}", orderId, seq, actor);
    }

    private static boolean isPackedOrLater(OrderStatus status) {
        return status == OrderStatus.FULFILLED || status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
