package com.oriah.procurement.service;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one door every receipt goes through, one at a time.
 *
 * <p>Stock is sold oldest first, by the time each lot was RECEIVED (FIFO by {@code received_at},
 * CLIENT_DECISIONS 3). That makes the order goods were received in part of the books, so two
 * receipts must not run side by side, and two rules need the whole picture:
 * <ul>
 *   <li>each receipt gets a time later than the last one's, so "received later" always means
 *       "received after", even if the server's clock steps backwards;</li>
 *   <li>the OPENING stock (what the shop already held on day one) must be received before any
 *       shipment. Received after one, it would be counted as newer than a shipment it predates
 *       and sold in the wrong order.</li>
 * </ul>
 *
 * <p>It is an advisory lock held to the end of the transaction, taken FIRST: lock order is this
 * gate, then the consignment's row, then its purchase orders, then (last) the outbox's own lock.
 * Nothing takes them in another order, so receipts cannot deadlock.
 */
@Component
public class ReceiptGate {

    /** The letters "ORIARCPT" read as one number. Nothing else in this service may use it. */
    static final long RECEIPT_LOCK_KEY = 0x4F52_4941_5243_5054L;

    private final JdbcTemplate jdbc;

    public ReceiptGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Waits for any receipt in progress to finish, and holds every later one back until this one ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enter() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {
        }, RECEIPT_LOCK_KEY);
    }

    /** Has any shipment (anything but the opening stock) been received? Meaningful only after {@link #enter}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean anyShipmentReceived() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM consignment WHERE status = 'RECEIVED' AND NOT is_opening)", Boolean.class));
    }

    /** When the latest receipt landed, or null if nothing has been received. Meaningful only after {@link #enter}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant latestReceipt() {
        return jdbc.queryForObject("SELECT max(received_at) FROM consignment WHERE status = 'RECEIVED'",
                (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant());
    }
}
