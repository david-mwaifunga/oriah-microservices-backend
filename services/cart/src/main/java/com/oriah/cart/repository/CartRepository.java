package com.oriah.cart.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.cart.model.Cart;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Cart> findByKeycloakId(String keycloakId);
}
