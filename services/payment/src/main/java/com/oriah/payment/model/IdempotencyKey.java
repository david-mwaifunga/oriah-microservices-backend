package com.oriah.payment.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * Persisted record of a previously-seen {@code Idempotency-Key} on
 * {@code POST /api/v1/payments/intents/&#123;id&#125;/confirm}. A
 * repeat call with the same key (within a sensible TTL -- not
 * enforced yet; pruned by an out-of-band job in production) replays
 * {@link #responseJson()} verbatim and skips the gateway charge.
 *
 * <p>Inherits audit columns + soft-delete from
 * {@link BaseEntity} via the per-service JpaConfig.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey extends BaseEntity {

    /** The client-supplied key (typically a UUID). PK + unique. */
    @Id
    @Column(name = "key", length = 128, nullable = false)
    private String key;

    /** The PaymentIntent the original call resolved to. */
    @Column(name = "intent_id", nullable = false)
    private UUID intentId;

    /**
     * JSON-serialized PaymentIntentResponse from the original call.
     * Captured verbatim so the replay produces byte-identical output --
     * matches Stripe's idempotency-replay contract.
     */
    @Column(name = "response_json", columnDefinition = "text", nullable = false)
    private String responseJson;

    /** JPA-only no-args ctor. */
    protected IdempotencyKey() {
    }

    public IdempotencyKey(String key, UUID intentId, String responseJson) {
        this.key = key;
        this.intentId = intentId;
        this.responseJson = responseJson;
    }

    public String key() {
        return key;
    }

    public UUID intentId() {
        return intentId;
    }

    public String responseJson() {
        return responseJson;
    }
}
