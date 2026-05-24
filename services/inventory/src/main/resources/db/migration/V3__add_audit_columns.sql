-- Phase 4.1.6 -- audit + soft-delete columns on inventory + product_index.
-- See catalog/V2__add_audit_columns.sql header for the pattern.

ALTER TABLE inventory ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE inventory ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE product_index ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE product_index ALTER COLUMN created_at DROP DEFAULT;
-- product_index.updated_at exists from V2 (NOT NULL DEFAULT now()); drop NOT NULL so AuditableEntity's nullable mapping validates.
ALTER TABLE product_index ALTER COLUMN updated_at DROP NOT NULL;
ALTER TABLE product_index ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE product_index ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE product_index ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
