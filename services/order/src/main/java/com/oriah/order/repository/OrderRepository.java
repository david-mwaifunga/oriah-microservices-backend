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

    @Override
    @EntityGraph(attributePaths = "items")
    Page<Order> findAll(Pageable pageable);
}
