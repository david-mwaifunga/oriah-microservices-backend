-- Supporting indexes for GET /api/v1/admin/payments.
--
-- Before this, payment_intents had indexes on (order_id) and (keycloak_id)
-- only -- both lookup paths for a single row. The admin list is the first
-- query that SCANS the table: newest-first, optionally narrowed by status,
-- always excluding soft-deleted rows.
--
-- PARTIAL indexes, not a plain composite, and the reason is the leading
-- column. `(status, created_at DESC)` cannot serve the unfiltered list at all,
-- because status is leftmost and the unfiltered query has no status predicate.
-- Since `is_deleted = false` is present in BOTH queries, it belongs in the
-- WHERE clause of the index rather than as a column: the index then covers
-- exactly the live rows and stays smaller as soft-deleted rows accumulate.
--
--   no filter : WHERE is_deleted = false            ORDER BY created_at DESC
--   filtered  : WHERE is_deleted = false AND status ORDER BY created_at DESC
--
-- Forward-only, per the Flyway discipline: this adds indexes and touches no
-- data or column definitions, so it is safe to apply to a populated table.

CREATE INDEX IF NOT EXISTS idx_payment_intents_live_created
    ON payment_intents (created_at DESC)
    WHERE is_deleted = false;

CREATE INDEX IF NOT EXISTS idx_payment_intents_live_status_created
    ON payment_intents (status, created_at DESC)
    WHERE is_deleted = false;
