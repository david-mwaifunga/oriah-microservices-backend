package com.oriah.order.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.order.model.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<Order> findById(UUID id);

    @EntityGraph(attributePaths = "items")
    Page<Order> findByKeycloakIdOrderByPlacedAtDesc(String keycloakId, Pageable pageable);

    /** The order a shopper's Idempotency-Key already made, if any. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByKeycloakIdAndIdempotencyKey(String keycloakId, String idempotencyKey);

    /** A recorded (phone) order by its Idempotency-Key: one key is used once among them, whoever recorded them (V13's unique index). */
    Optional<Order> findByChannelAndIdempotencyKey(com.oriah.order.model.Channel channel, String idempotencyKey);

    /** The shopper's live order for this very bag (the quote's fingerprint), if one is still waiting for payment. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findFirstByKeycloakIdAndQuoteDigestAndStatusIn(String keycloakId, String quoteDigest,
                                                                  java.util.Collection<com.oriah.order.model.OrderStatus> statuses);

    @Override
    @EntityGraph(attributePaths = "items")
    Page<Order> findAll(Pageable pageable);
}
