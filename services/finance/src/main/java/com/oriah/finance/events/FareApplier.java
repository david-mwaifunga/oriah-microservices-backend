package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.events.inbound.OrderShipped;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies {@code order.shipped}: the courier fare Oriah paid, as an expense of the Lusaka day the parcel went. Every dispatch is remembered ({@code dispatch_fact}) with or
 * without a fare, which is what lets a fare typed in by hand follow it and never precede it: the dispatch event is therefore always applied before any typed fare, and the
 * two can never both be counted. A fare of K0.00, no fare (a courier the recipient paid, or Oriah's own delivery) posts nothing.
 *
 * <p>The posted fare gets an expense record of its own (origin POSTED, by {@code system:finance}, a description that names nothing), so it can be reversed or corrected in
 * finance like any other expense.
 */
@Component
public class FareApplier {

    static final String SYSTEM = "system:finance";
    static final String DESCRIPTION = "Courier fare paid at dispatch";

    private final JdbcTemplate jdbc;
    private final Locks locks;
    private final LedgerWriter ledger;
    private final BusinessCalendar calendar;

    public FareApplier(JdbcTemplate jdbc, Locks locks, LedgerWriter ledger, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.ledger = ledger;
        this.calendar = calendar;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        OrderShipped s = read(payload);
        if (s.orderId() == null) {
            throw new IllegalArgumentException("order.shipped has no orderId");
        }
        OrderShipped.Dispatch dispatch = s.dispatch();
        Instant when = ctx.occurredAt();
        BigDecimal fare = null;
        String payer = null;
        if (dispatch != null) {
            if (dispatch.dispatchedAt() != null) {
                if (ctx.occurredAt() == null || !ctx.occurredAt().truncatedTo(ChronoUnit.MICROS).equals(dispatch.dispatchedAt().truncatedTo(ChronoUnit.MICROS))) {
                    throw new IllegalArgumentException("the envelope says the parcel went at " + ctx.occurredAt() + " but the body says dispatchedAt " + dispatch.dispatchedAt());
                }
                when = dispatch.dispatchedAt();
            }
            fare = dispatch.farePaid();
            payer = dispatch.payer();
            if (fare != null) {
                SaleApplier.ngwee(fare, "a fare");
                if (fare.signum() < 0) {
                    throw new IllegalArgumentException("a fare is not below nothing, was " + fare.toPlainString());
                }
            }
        }
        if (when == null) {
            throw new IllegalArgumentException("order.shipped says no day the parcel went");
        }

        locks.writer();
        locks.order(s.orderId());
        int remembered = jdbc.update("INSERT INTO dispatch_fact (order_id, dispatched_at, fare_paid, payer, source_event_id) VALUES (?, ?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING",
                s.orderId(), Timestamp.from(when), fare, payer, ctx.eventId());
        if (remembered == 0 || fare == null || fare.signum() == 0) {
            return;   // told again, or nothing was paid
        }
        long entryId = ledger.post(Entry.of("fare:" + s.orderId(), "EXPENSE", fare, when, calendar.dayOf(when), "ORDER_SHIPPED", ctx.actor())
                .category("COURIER_FARE").sourceRecordedAt(ctx.recordedAt()).event(ctx.eventId(), ctx.producer(), ctx.outboxId()).order(s.orderId())).entryId();
        jdbc.update("""
                INSERT INTO expense (expense_id, ledger_entry_id, kind, category, description, linked_order_id, origin, created_by)
                VALUES (?, ?, 'ADD', 'COURIER_FARE', ?, ?, 'POSTED', ?)""", UUID.randomUUID(), entryId, DESCRIPTION, s.orderId(), SYSTEM);
    }

    private static OrderShipped read(byte[] payload) {
        try {
            return EventJson.mapper().readValue(payload, OrderShipped.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("order.shipped cannot be read: " + e.getOriginalMessage(), e);
        }
    }
}
