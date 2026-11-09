package com.oriah.payment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.payment.model.IdempotencyKey;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Claims the key for this intent, RESERVED, unless it already exists.
     *
     * <p>A plain INSERT that skips on conflict, rather than "look it up, then
     * save": two requests carrying the same key can both look it up and both
     * find nothing, but the database lets exactly one INSERT in. The other
     * waits for the first transaction to finish, then inserts nothing, and
     * reads the winner's row instead.
     *
     * @return 1 when this call claimed the key, 0 when it was already there
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_keys (key, intent_id, state, created_at, created_by, is_deleted)
            VALUES (:key, :intentId, 'RESERVED', now(), :subject, false)
            ON CONFLICT (key) DO NOTHING""", nativeQuery = true)
    int reserve(@Param("key") String key, @Param("intentId") UUID intentId, @Param("subject") String subject);

    List<IdempotencyKey> findByIntentIdAndState(UUID intentId, IdempotencyKey.State state);
}
