-- Phase 4.2.4 -- idempotency keys for POST /payments/intents/{id}/confirm.
--
-- Clients (the checkout orchestrator + future frontend) include an
-- Idempotency-Key header. If the same key is seen twice, the second
-- call replays the original response without re-charging the payment
-- gateway. UNIQUE on key is enforced via PRIMARY KEY.

CREATE TABLE idempotency_keys (
    key            VARCHAR(128) NOT NULL PRIMARY KEY,
    intent_id      UUID         NOT NULL,
    response_json  TEXT         NOT NULL,
    -- Audit columns from AuditableEntity (matching the pattern in
    -- V2__add_audit_columns.sql for payment_intents).
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP,
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    is_deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Index for the FK-like join from idempotency_keys back to payment_intents,
-- though we don't enforce a real FK -- the intent might be deleted
-- separately, in which case the cached key is harmless (replayed
-- response remains valid).
CREATE INDEX idx_idempotency_keys_intent_id ON idempotency_keys (intent_id);
