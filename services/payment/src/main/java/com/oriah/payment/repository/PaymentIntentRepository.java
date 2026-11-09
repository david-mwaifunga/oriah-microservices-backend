package com.oriah.payment.repository;

import java.time.Instant;
import java.util.Collection;
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
    List<PaymentIntent> findByKeycloakId(String keycloakId);

    /**
     * The order's intent in one of {@code statuses}, if it has one. Called
     * with {@link PaymentStatus#LIVE}, of which an order has at most one -- the
     * partial unique index uq_payment_intents_one_live_per_order (V5) -- so
     * one Optional is enough. (A {@code findByOrderId} returning one Optional
     * would not be: an order can have several intents -- failed ones and
     * their replacement.)
     */
    Optional<PaymentIntent> findByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);

    /** A charge whose answer payment is still waiting for: the intent, and the reference the gateway knows it by. */
    record ChargeInFlight(UUID intentId, UUID chargeReference, Instant since) {}

    /**
     * The intents that have been CHARGING since before {@code cutoff}, oldest
     * first: the reconciler's work list.
     *
     * <p>updated_at is when the intent went CHARGING. Nothing writes a
     * CHARGING intent except the step that settles it (the admin side is
     * read-only), so its last write is the move into CHARGING.
     *
     * <p>An intent with no updated_at at all can only have been written by
     * hand. It counts as old: the safe mistake is to ask the gateway about a
     * charge too early (a void is final, and a charge still in flight then
     * takes nothing), never to leave a charge unsettled for ever.
     */
    @Query("""
            select new com.oriah.payment.repository.PaymentIntentRepository$ChargeInFlight(
                       p.id, p.chargeReference, p.updatedAt)
            from PaymentIntent p
            where p.status = com.oriah.payment.model.PaymentStatus.CHARGING
              and (p.updatedAt is null or p.updatedAt < :cutoff)
            order by p.updatedAt asc nulls first
            """)
    List<ChargeInFlight> findChargingStartedBefore(@Param("cutoff") Instant cutoff);

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
