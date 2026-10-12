package com.oriah.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    Optional<PaymentIntent> findByOrderId(UUID orderId);
    List<PaymentIntent> findByKeycloakId(String keycloakId);

    /**
     * Admin list. `deleted` is BaseEntity's soft-delete flag mapped to
     * is_deleted; filtering it here rather than in the controller keeps the
     * predicate next to the partial index built for it
     * (V4__index_payment_intents_admin_list.sql). Ordering comes from the
     * Pageable's Sort so the two cannot disagree.
     */
    Page<PaymentIntent> findByDeletedFalse(Pageable pageable);

    Page<PaymentIntent> findByDeletedFalseAndStatus(PaymentStatus status, Pageable pageable);
}
