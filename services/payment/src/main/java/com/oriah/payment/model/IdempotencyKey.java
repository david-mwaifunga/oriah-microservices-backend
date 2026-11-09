package com.oriah.payment.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * An {@code Idempotency-Key} seen on
 * {@code POST /api/v1/payments/intents/&#123;id&#125;/confirm}: the client's
 * promise that every request carrying it is the same request.
 *
 * <h2>Reserved before the charge, completed after it</h2>
 *
 * The key used to be written only after the charge, with its response, so
 * two requests carrying it at once both missed it and both charged. Now:
 * <ul>
 *   <li>{@link State#RESERVED} -- written (by a plain INSERT the database lets
 *       in once) in the same transaction that moves the intent to CHARGING,
 *       before the gateway is called. A request that finds the key RESERVED
 *       is refused: the first one is still at the gateway, or its outcome is
 *       still being settled;</li>
 *   <li>{@link State#COMPLETED} -- written with {@link #responseJson()} when
 *       the outcome is recorded. A request that finds it is served that
 *       response verbatim, and the gateway is not called.</li>
 * </ul>
 * The key is bound to one intent; presented against another it is refused.
 * Pruned by an out-of-band job in production (no TTL is enforced yet).
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey extends BaseEntity {

    /** The longest key the column holds; a longer one is refused before anything is charged. */
    public static final int MAX_LENGTH = 128;

    public enum State {
        RESERVED,
        COMPLETED
    }

    /** The client-supplied key (typically a UUID). PK + unique. */
    @Id
    @Column(name = "key", length = MAX_LENGTH, nullable = false)
    private String key;

    /** The PaymentIntent the key is bound to. */
    @Column(name = "intent_id", nullable = false)
    private UUID intentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private State state;

    /**
     * The PaymentIntentResponse to replay, once COMPLETED. Captured verbatim
     * so the replay produces byte-identical output -- Stripe's
     * idempotency-replay contract.
     */
    @Column(name = "response_json", columnDefinition = "text")
    private String responseJson;

    /** JPA-only no-args ctor. Keys are created RESERVED by IdempotencyKeyRepository.reserve. */
    protected IdempotencyKey() {
    }

    public String key() {
        return key;
    }

    public UUID intentId() {
        return intentId;
    }

    public State state() {
        return state;
    }

    public String responseJson() {
        return responseJson;
    }

    /** RESERVED -&gt; COMPLETED with the response every later request with this key is served. */
    public void complete(String responseJson) {
        if (state != State.RESERVED) {
            throw new IllegalStateException("Idempotency-Key " + key + " is already " + state);
        }
        if (responseJson == null || responseJson.isBlank()) {
            throw new IllegalArgumentException("A completed Idempotency-Key needs the response it replays");
        }
        this.state = State.COMPLETED;
        this.responseJson = responseJson;
    }
}
