package com.oriah.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.payment.model.PaymentIntent;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    Optional<PaymentIntent> findByOrderId(UUID orderId);
    List<PaymentIntent> findByKeycloakId(String keycloakId);
}
