package com.oriah.order.service;

import java.sql.Array;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.oriah.order.dto.OrderResponse;
import com.oriah.order.model.Order;

/**
 * Builds an order's answer WITH its dispatch dates (Phase 8): the one place every reply site goes through, so a page of orders reads its steps in ONE query
 * ({@code order_id = ANY(?)}) however many orders it holds, never one per order. The courier fare is deliberately not read here: it is cost, and an order's
 * answer never carries it.
 */
@Component
public class DispatchViews {

    private final JdbcTemplate jdbc;

    public DispatchViews(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The order as its shopper may read it, with its dispatch dates. */
    public OrderResponse shopper(Order order) {
        return OrderResponse.from(order).withDispatch(stepsOf(List.of(order.id())).get(order.id()));
    }

    /** The order as staff read it, with its dispatch dates. */
    public OrderResponse staff(Order order) {
        return OrderResponse.forStaff(order).withDispatch(stepsOf(List.of(order.id())).get(order.id()));
    }

    public Optional<OrderResponse> shopper(Optional<Order> order) {
        return order.map(this::shopper);
    }

    public Optional<OrderResponse> staff(Optional<Order> order) {
        return order.map(this::staff);
    }

    public Page<OrderResponse> shopper(Page<Order> page) {
        Map<UUID, OrderResponse.Dispatch> steps = stepsOf(page.map(Order::id).getContent());
        return page.map(o -> OrderResponse.from(o).withDispatch(steps.get(o.id())));
    }

    public Page<OrderResponse> staff(Page<Order> page) {
        Map<UUID, OrderResponse.Dispatch> steps = stepsOf(page.map(Order::id).getContent());
        return page.map(o -> OrderResponse.forStaff(o).withDispatch(steps.get(o.id())));
    }

    /** The dispatch dates of these orders, in one read; an order with no step is not in the map. */
    Map<UUID, OrderResponse.Dispatch> stepsOf(Collection<UUID> orderIds) {
        Map<UUID, OrderResponse.Dispatch> out = new HashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        Map<UUID, Instant[]> dates = new HashMap<>();
        Map<UUID, String> references = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT order_id, step, occurred_at, reference FROM order_dispatch_step WHERE order_id = ANY(?)");
            Array ids = con.createArrayOf("uuid", orderIds.toArray());
            ps.setArray(1, ids);
            return ps;
        }, rs -> {
            UUID id = rs.getObject("order_id", UUID.class);
            Instant[] d = dates.computeIfAbsent(id, k -> new Instant[3]);
            Instant at = rs.getTimestamp("occurred_at").toInstant();
            switch (rs.getString("step")) {
                case "PACKED" -> d[0] = at;
                case "DISPATCHED" -> {
                    d[1] = at;
                    references.put(id, rs.getString("reference"));
                }
                default -> d[2] = at;
            }
        });
        dates.forEach((id, d) -> out.put(id, new OrderResponse.Dispatch(d[0], d[1], d[2], references.get(id))));
        return out;
    }
}
