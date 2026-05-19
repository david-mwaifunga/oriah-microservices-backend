package com.oriah.promotion.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "promotions")
public class Promotion {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PromotionType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column
    private String description;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "max_redemptions", nullable = false)
    private Integer maxRedemptions;

    @Column(name = "current_redemptions", nullable = false)
    private Integer currentRedemptions;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Promotion() {}

    public Promotion(String code, PromotionType type, BigDecimal amount, String description,
                     Instant validUntil, int maxRedemptions) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (type == PromotionType.PERCENTAGE && amount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("percentage discount cannot exceed 100");
        }
        this.id = UUID.randomUUID();
        this.code = code.toUpperCase();
        this.type = type;
        this.amount = amount;
        this.description = description;
        this.validFrom = Instant.now();
        this.validUntil = validUntil;
        this.maxRedemptions = maxRedemptions;
        this.currentRedemptions = 0;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public PromotionType type() { return type; }
    public BigDecimal amount() { return amount; }
    public String description() { return description; }
    public Instant validFrom() { return validFrom; }
    public Instant validUntil() { return validUntil; }
    public Integer maxRedemptions() { return maxRedemptions; }
    public Integer currentRedemptions() { return currentRedemptions; }
    public boolean isEnabled() { return enabled; }
    public Instant createdAt() { return createdAt; }

    /**
     * Checks all the runtime guards: enabled, in date range, redemption
     * cap not exhausted. Used by /promotions/{code} validate before
     * returning a discount calculation to checkout.
     */
    public boolean isRedeemable(Instant now) {
        if (!enabled) return false;
        if (now.isBefore(validFrom)) return false;
        if (validUntil != null && now.isAfter(validUntil)) return false;
        if (maxRedemptions > 0 && currentRedemptions >= maxRedemptions) return false;
        return true;
    }

    /**
     * Apply the discount to a subtotal. Result is never negative — a
     * FIXED amount larger than subtotal clamps to subtotal (free order).
     */
    public BigDecimal computeDiscount(BigDecimal subtotal) {
        BigDecimal raw = switch (type) {
            case FIXED -> amount;
            case PERCENTAGE -> subtotal.multiply(amount).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        };
        return raw.min(subtotal); // never discount more than the cart total
    }

    public void redeem() {
        this.currentRedemptions++;
    }

    public void disable() {
        this.enabled = false;
    }
}
