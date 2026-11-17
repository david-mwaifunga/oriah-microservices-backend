package com.oriah.order.service;

import com.oriah.order.dto.OrderResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an order's own answer says about its corrections (Phase 12, plan 4.10): each paid cancellation or return with what the customer is owed for it,
 * and the exchange link. Read in ONE query per page ({@code order_id = ANY(?)}), like the dispatch dates.
 *
 * <p>An allow-list. Nobody's answer carries a note, a back-dating reason, a cost or a payout. Staff (an order administrator) also read why and who
 * recorded it, and see every attempt; the shopper and a salesperson see only corrections that are DONE, with no reason and no actor.
 */
@Component
public class CorrectionViews {

    private final JdbcTemplate jdbc;

    public CorrectionViews(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The corrections of these orders, oldest first; an order with none is not in the map. */
    public Map<UUID, List<OrderResponse.Correction>> of(Collection<UUID> orderIds, boolean staff) {
        Map<UUID, List<OrderResponse.Correction>> out = new HashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT c.id, c.order_id, c.kind, c.state, c.occurred_at, c.owed_gross, c.currency, c.reason_code, c.recorded_by,
                           (SELECT COALESCE(sum(l.quantity), 0) FROM order_correction_line l WHERE l.correction_id = c.id) AS pieces,
                           (SELECT e.new_order_id FROM order_exchange e WHERE e.return_id = c.id) AS exchanged_for
                      FROM order_correction c
                     WHERE c.order_id = ANY(?)""" + (staff ? "" : " AND c.state = 'DONE'") + " ORDER BY c.recorded_at, c.id");
            Array ids = con.createArrayOf("uuid", orderIds.toArray());
            ps.setArray(1, ids);
            return ps;
        }, rs -> {
            out.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>()).add(new OrderResponse.Correction(rs.getObject("id", UUID.class),
                    rs.getString("kind"), rs.getString("state"), rs.getTimestamp("occurred_at").toInstant(), rs.getInt("pieces"), rs.getBigDecimal("owed_gross"),
                    rs.getString("currency"), rs.getObject("exchanged_for", UUID.class), staff ? rs.getString("reason_code") : null,
                    staff ? rs.getString("recorded_by") : null));
        });
        return out;
    }

    /** For each of these orders that is the NEW order of an exchange: the return it was taken instead of. */
    public Map<UUID, OrderResponse.Exchange> exchangesFor(Collection<UUID> orderIds) {
        Map<UUID, OrderResponse.Exchange> out = new HashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT e.new_order_id, e.return_id, c.order_id FROM order_exchange e JOIN order_correction c ON c.id = e.return_id
                     WHERE e.new_order_id = ANY(?)""");
            Array ids = con.createArrayOf("uuid", orderIds.toArray());
            ps.setArray(1, ids);
            return ps;
        }, rs -> {
            out.put(rs.getObject("new_order_id", UUID.class), new OrderResponse.Exchange(rs.getObject("return_id", UUID.class), rs.getObject("order_id", UUID.class)));
        });
        return out;
    }
}
