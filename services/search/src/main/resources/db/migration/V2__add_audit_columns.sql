-- Phase 4.1.6 -- audit + soft-delete columns on search_index.
--
-- updated_at already exists from V1 (NOT NULL DEFAULT now()); we drop
-- the NOT NULL so AuditableEntity's nullable mapping validates.
-- Search rows are produced by the Kafka consumer thread, so their
-- created_by ends up as "system" via the KeycloakAuditorAware
-- fallback (no SecurityContext on the consumer thread).

ALTER TABLE search_index ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE search_index ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE search_index ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE search_index ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE search_index ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE search_index ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
