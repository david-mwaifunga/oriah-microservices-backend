package com.oriah.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.payment.dto.AdminPaymentSummary;
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

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(PaymentStatus status);

    /**
     * Captured money, summed in the database and grouped by currency.
     *
     * <p>Grouped rather than totalled because every row carries its own
     * currency and adding across them produces a number that means nothing.
     * Done as a projection so no PaymentIntent rows are loaded to add them up:
     * the dashboard asks this on every page view.
     */
    @Query("""
            select new com.oriah.payment.dto.AdminPaymentSummary$CurrencyTotal(
                       p.currency, sum(p.amount))
            from PaymentIntent p
            where p.deleted = false and p.status = :status
            group by p.currency
            order by p.currency
            """)
    List<AdminPaymentSummary.CurrencyTotal> sumAmountByCurrency(
            @Param("status") PaymentStatus status);
}
