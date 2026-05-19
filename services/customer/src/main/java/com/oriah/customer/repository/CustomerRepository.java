package com.oriah.customer.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.customer.model.Customer;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    /**
     * Finds the customer for a Keycloak {@code sub}. Used by the
     * self-provisioning {@code /me} endpoint to decide insert-or-update.
     *
     * <p>{@code @EntityGraph(addresses)} forces a LEFT JOIN FETCH so
     * the addresses collection is populated inside the same query —
     * the DTO mapping in the controller can read it after the session
     * closes (open-in-view: false in our config).
     */
    @EntityGraph(attributePaths = "addresses")
    Optional<Customer> findByKeycloakId(String keycloakId);

    @Override
    @EntityGraph(attributePaths = "addresses")
    Optional<Customer> findById(UUID id);
}
