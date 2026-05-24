-- Phase 4.1.6 -- audit + soft-delete columns for the catalog domain.
--
-- AuditableEntity (in shared/oriah-jpa-base) declares these as
-- nullable JPA columns; Spring Data Auditing populates the audit
-- four on persist/update, and Hibernate's @SoftDelete manages
-- is_deleted (any repository.delete(x) call becomes an UPDATE that
-- flips this flag; subsequent queries auto-filter rows where
-- is_deleted = true).
--
-- IF NOT EXISTS guards keep the migration safe even if products
-- already happened to have any of these columns; on a green-field
-- volume they all add. DEFAULT NOW() / DEFAULT FALSE seeds any
-- pre-existing rows; we then DROP the defaults so subsequent inserts
-- get their values from the JPA listener (created_at) or the
-- application (is_deleted is set by @SoftDelete on save).

ALTER TABLE products ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE products ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE products ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE products ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE products ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
