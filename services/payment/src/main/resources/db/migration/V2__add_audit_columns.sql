-- Phase 4.1.6 -- audit + soft-delete columns on payment_intents.
-- created_at was already there NOT NULL from V1; drop the NOT NULL.

ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE payment_intents ALTER COLUMN created_at DROP NOT NULL;
ALTER TABLE payment_intents ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
