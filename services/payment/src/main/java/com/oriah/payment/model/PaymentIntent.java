package com.oriah.payment.model;

import com.oriah.money.Ngwee;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.oriah.jpa.BaseEntity;

/**
 * "We plan to take this much, in this currency, for this order" -- and,
 * once confirmed, what the gateway said.
 *
 * <pre>
 *   PENDING --startCharging(ref)--&gt; CHARGING --markSucceeded(gwRef, at)--&gt; SUCCEEDED
 *                                          \--markFailed(reason, at)----&gt; FAILED
 * </pre>
 *
 * <p>CHARGING is recorded, with the reference the gateway will be called
 * with, and committed BEFORE the gateway is called. That ordering is the whole
 * point: while the charge is in flight the database already says so, so a
 * retry or a second request sees it and cannot start another, and the only
 * way out of CHARGING is an answer from the gateway about that very charge.
 * (It used to be one transaction around the charge: a failure after the charge
 * rolled back to PENDING with no trace, and the next confirm charged again.)
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntent extends BaseEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    /**
     * The optimistic lock. Two requests that both read this intent as PENDING
     * both try to move it to CHARGING; the second one's update is refused
     * because the version it read is no longer current, so only one of them
     * goes on to charge. A wrapper type, so Spring Data sees a new intent
     * (version still null) as new and inserts it without a lookup first.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "keycloak_id", nullable = false)
    private String keycloakId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    /** When the gateway decided -- captured or declined -- never when we heard. */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** The reference the gateway is called with; set on the way into CHARGING. */
    @Column(name = "charge_reference")
    private UUID chargeReference;

    /** The gateway's own id for the capture. */
    @Column(name = "gateway_reference")
    private String gatewayReference;

    protected PaymentIntent() {}

    public PaymentIntent(UUID orderId, String keycloakId, BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        Ngwee.require(amount, "amount");
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.keycloakId = keycloakId;
        this.amount = amount;
        // Fails fast rather than defaulting. The old default was "USD" on a
        // Kwacha platform, and because the service substituted one too, the
        // same decision lived in two places with nothing keeping them in step.
        // Every construction site -- the service, and every test -- passes a
        // currency explicitly, so there is nothing for a default to serve
        // except hiding a caller that forgot.
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "A payment intent must name its currency; money with no unit is not an amount.");
        }
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
    }

    public UUID id() { return id; }
    public Long version() { return version; }
    public UUID orderId() { return orderId; }
    public String keycloakId() { return keycloakId; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public PaymentStatus status() { return status; }
    public String failureReason() { return failureReason; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID chargeReference() { return chargeReference; }
    public String gatewayReference() { return gatewayReference; }

    /**
     * PENDING -&gt; CHARGING, naming the charge about to be made. Only a PENDING
     * intent can be charged: one already being charged has a charge in
     * flight, and a settled one has had its answer.
     */
    public void startCharging(UUID chargeReference) {
        if (chargeReference == null) {
            throw new IllegalArgumentException("A charge needs its reference: without one, nobody could ever "
                    + "ask the gateway how it ended.");
        }
        if (status == PaymentStatus.CHARGING) {
            throw new IllegalStateException("PaymentIntent " + id + " is being charged; its outcome is not "
                    + "known yet. It will not be charged again.");
        }
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("PaymentIntent " + id + " already in terminal state " + status);
        }
        this.status = PaymentStatus.CHARGING;
        this.chargeReference = chargeReference;
    }

    /** CHARGING -&gt; SUCCEEDED: the gateway captured the money, at {@code capturedAt}. */
    public void markSucceeded(String gatewayReference, Instant capturedAt) {
        requireCharging();
        if (gatewayReference == null || gatewayReference.isBlank()) {
            throw new IllegalArgumentException("A capture needs the gateway's reference for it.");
        }
        requireDecisionTime(capturedAt);
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayReference = gatewayReference;
        this.confirmedAt = capturedAt;
    }

    /** CHARGING -&gt; FAILED: the charge did not take money, for {@code reason}, decided at {@code decidedAt}. */
    public void markFailed(String reason, Instant decidedAt) {
        requireCharging();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A failed charge needs its reason.");
        }
        requireDecisionTime(decidedAt);
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.confirmedAt = decidedAt;
    }

    private void requireCharging() {
        if (status != PaymentStatus.CHARGING) {
            throw new IllegalStateException("PaymentIntent " + id + " is not being charged (it is " + status
                    + "), so no charge outcome can settle it");
        }
    }

    /**
     * The gateway's time is required, never defaulted to now: a payment is
     * recorded at the moment it happened, even when that is learned later.
     */
    private static void requireDecisionTime(Instant decidedAt) {
        if (decidedAt == null) {
            throw new IllegalArgumentException("A charge outcome needs the time the gateway decided it.");
        }
    }
}
