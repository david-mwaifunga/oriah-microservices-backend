package com.oriah.order.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.model.Order;
import com.oriah.order.repository.OrderRepository;

/**
 * The one way to take an order's row lock before deciding something about it (Phase 8, design D12): {@code SELECT ... FOR UPDATE}, and ONLY THEN load the
 * order. The order matters: an entity loaded before the lock may already be stale by the time the lock is granted (this is the trap
 * {@code SettlementService.lockOrder} documents), so a caller must never have loaded the order earlier in the same transaction.
 *
 * <p>Taken by everything that changes an order's physical life after it is paid and cannot rely on {@code @Version} alone: the dispatch steps, the box
 * swap's plan and record, and the staff door to SHIPPED. Two of them at once queue behind each other, and the second judges the order as the first left it.
 */
@Component
public class OrderLocks {

    private final JdbcTemplate jdbc;
    private final OrderRepository repository;

    public OrderLocks(JdbcTemplate jdbc, OrderRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    /**
     * @return the order, locked until the current transaction ends; empty when there is no such order
     * @throws org.springframework.transaction.IllegalTransactionStateException when called outside a transaction (a lock held for no time is no lock)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> lock(UUID orderId) {
        List<UUID> found = jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE", UUID.class, orderId);
        return found.isEmpty() ? Optional.empty() : repository.findById(orderId);
    }
}
