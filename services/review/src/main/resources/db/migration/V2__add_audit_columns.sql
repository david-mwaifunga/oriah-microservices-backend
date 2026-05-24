-- Phase 4.1.6 -- audit + soft-delete columns on reviews.
-- created_at was already there NOT NULL from V1; drop the NOT NULL.

ALTER TABLE reviews ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE reviews ALTER COLUMN created_at DROP NOT NULL;
ALTER TABLE reviews ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
