package com.oriah.payment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent extends BaseEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

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

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected PaymentIntent() {}

    public PaymentIntent(UUID orderId, String keycloakId, BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.keycloakId = keycloakId;
        this.amount = amount;
        this.currency = currency == null || currency.isBlank() ? "USD" : currency;
        this.status = PaymentStatus.PENDING;
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public String keycloakId() { return keycloakId; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public PaymentStatus status() { return status; }
    public String failureReason() { return failureReason; }
    public Instant confirmedAt() { return confirmedAt; }

    public void markSucceeded() {
        requirePending();
        this.status = PaymentStatus.SUCCEEDED;
        this.confirmedAt = Instant.now();
    }

    public void markFailed(String reason) {
        requirePending();
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.confirmedAt = Instant.now();
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("PaymentIntent " + id + " already in terminal state " + status);
        }
    }
}
