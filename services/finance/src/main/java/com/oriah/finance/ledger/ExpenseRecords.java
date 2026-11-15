package com.oriah.finance.ledger;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expense record of a row the SYSTEM posted (a courier fare, a payment charge, a bill for the month): so a reversal or a correction has the same identity to point at
 * whichever way an expense arrived. By {@code system:finance}, with a description that names nothing and, for a reversal, a fixed reason (never an event's own words).
 */
@Component
public class ExpenseRecords {

    public static final String SYSTEM = "system:finance";

    private final JdbcTemplate jdbc;

    public ExpenseRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void posted(long ledgerEntryId, String kind, String category, String description, String reason, UUID linkedOrderId, UUID linkedConfirmationId) {
        jdbc.update("""
                INSERT INTO expense (expense_id, ledger_entry_id, kind, category, description, reason, linked_order_id, linked_confirmation_id, origin, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?)""",
                UUID.randomUUID(), ledgerEntryId, kind, category, description, reason, linkedOrderId, linkedConfirmationId, SYSTEM);
    }
}
