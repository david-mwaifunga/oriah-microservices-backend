-- Phase 4.1.6 -- audit + soft-delete columns on notification_log.

ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE notification_log ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
