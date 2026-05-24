-- Phase 4.1.6 -- audit + soft-delete columns. customers and addresses
-- already had a hand-rolled created_at NOT NULL from V1; we DROP the
-- NOT NULL so AuditableEntity's nullable mapping validates AND so
-- @DataJpaTest saves (which don't load JpaConfig and so don't fire
-- the listener) still succeed.

ALTER TABLE customers ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE customers ALTER COLUMN created_at DROP NOT NULL;
ALTER TABLE customers ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE addresses ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE addresses ALTER COLUMN created_at DROP NOT NULL;
ALTER TABLE addresses ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
