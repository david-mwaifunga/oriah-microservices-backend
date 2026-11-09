-- Phase 1, item 3: charge once, record every outcome, announce it reliably.
--
-- Until now payment charged the card INSIDE the transaction that recorded the
-- result. If anything failed after the charge, the rollback put the intent
-- back to PENDING with no trace of the charge, and the next confirm charged
-- the card again. Two confirms at the same moment both charged. This
-- migration gives payment what it needs to do it the safe way round:
--
--   * a CHARGING state, recorded and committed BEFORE the gateway is called,
--     with the charge reference the gateway is called with -- so a charge in
--     flight is always on record, and a retry can see it;
--   * a version number on each intent, so two requests that read the same
--     PENDING intent cannot both move it to CHARGING;
--   * one intent per order that is pending, being charged or paid -- a
--     second one would be a second way to take the same money;
--   * Idempotency-Keys that are reserved before the charge and completed
--     after it, so a repeated request waits for, or replays, the first;
--   * the fake gateway's memory, so it answers like a real one: the same
--     reference always gets the same answer, even after a restart;
--   * the event log (outbox, relay cursor, processed_event), copied verbatim
--     from shared/oriah-events, so each outcome is announced exactly as it
--     was recorded.
--
-- Forward-only. Every statement below adds to the schema or settles rows that
-- would break the new unique index; nothing is dropped.

-- ---------------------------------------------------------------------------
-- 1. Settle existing duplicates, before the index that forbids them.
--
-- A V4 service let an order have any number of intents. The new index allows
-- one that is PENDING, CHARGING or SUCCEEDED per order, so rows that break it
-- are settled first, the same way on every database:
--
--   * two SUCCEEDED intents for one order mean the shopper may have paid
--     twice. No migration can decide which payment stands, so it stops here,
--     names the order, and changes nothing (Postgres rolls the whole
--     migration back) -- a person sorts it out, then the service starts;
--   * otherwise the order keeps its SUCCEEDED intent if it has one, else its
--     newest PENDING one (latest created_at; an intent with no date counts as
--     the oldest; equal dates are broken by id, so the choice never depends
--     on the order rows happen to be read in). Every other PENDING intent of
--     that order becomes FAILED, saying which intent superseded it and that
--     no charge was recorded for it.
--
-- Failing a PENDING intent moves no money. Whether one was ever charged
-- cannot be known, so the reason does not claim it: V4 charged inside the
-- transaction that recorded the result, a failure after the charge rolled the
-- intent back to PENDING, and V4's fake gateway kept no record. What is on
-- record is that no charge was, so that is what the reason says, and
-- confirmed_at stays empty -- no decision was ever recorded. No
-- payment.failed event is written: no charge outcome was recorded, and the
-- event log starts with this migration.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    doubled TEXT;
BEGIN
    SELECT string_agg(format('order %s has %s SUCCEEDED payment intents (%s)', order_id, paid, ids), '; '
                      ORDER BY order_id)
      INTO doubled
      FROM (SELECT order_id, count(*) AS paid, string_agg(id::text, ', ' ORDER BY id) AS ids
              FROM payment_intents
             WHERE status = 'SUCCEEDED'
             GROUP BY order_id
            HAVING count(*) > 1) AS paid_twice;
    IF doubled IS NOT NULL THEN
        RAISE EXCEPTION 'V5 stopped: % -- the shopper may have been charged more than once. Decide which payment stands (refund the other), mark the other intent FAILED or REFUNDED by hand, then start payment again.', doubled;
    END IF;
END
$$;

WITH ranked AS (
    SELECT id,
           first_value(id) OVER by_order AS survivor,
           row_number()    OVER by_order AS place
      FROM payment_intents
     WHERE status IN ('PENDING', 'SUCCEEDED')
    WINDOW by_order AS (PARTITION BY order_id
                        ORDER BY (status = 'SUCCEEDED') DESC, created_at DESC NULLS LAST, id DESC)
)
UPDATE payment_intents p
   SET status         = 'FAILED',
       failure_reason = 'Superseded by payment intent ' || r.survivor
                        || ' when V5 allowed one live intent per order; no charge was recorded for it',
       updated_at     = now(),
       updated_by     = 'flyway:V5'
  FROM ranked r
 WHERE p.id = r.id
   AND r.place > 1;

-- ---------------------------------------------------------------------------
-- 2. payment_intents
-- ---------------------------------------------------------------------------

-- The optimistic lock (@Version). Two requests that read the same PENDING
-- intent both try to move it to CHARGING; the second one's UPDATE finds the
-- version already moved and changes nothing, so only one of them charges.
ALTER TABLE payment_intents ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- The reference the gateway is called with, chosen and committed before the
-- call. The gateway treats a repeat of it as the same charge, and the
-- reconciler uses it to ask the gateway what became of a charge whose answer
-- never arrived. Unique: one capture must never settle two intents.
ALTER TABLE payment_intents ADD COLUMN charge_reference UUID NULL;
ALTER TABLE payment_intents
    ADD CONSTRAINT uq_payment_intents_charge_reference UNIQUE (charge_reference);

-- The gateway's own id for a captured charge (what a refund will quote).
ALTER TABLE payment_intents ADD COLUMN gateway_reference VARCHAR(255) NULL;

-- CHARGING joins the states. The list was never enforced before; it is now,
-- so a misspelt status cannot slip past the index below.
ALTER TABLE payment_intents
    ADD CONSTRAINT ck_payment_intents_status
        CHECK (status IN ('PENDING', 'CHARGING', 'SUCCEEDED', 'FAILED', 'REFUNDED'));

-- An intent in CHARGING is waiting on one particular charge; without its
-- reference nobody could ever find out how that charge ended.
ALTER TABLE payment_intents
    ADD CONSTRAINT ck_payment_intents_charging_has_reference
        CHECK (status <> 'CHARGING' OR charge_reference IS NOT NULL);

-- One intent per order that can still take money or already has. A FAILED
-- one does not count, so a shopper whose card was declined can try again.
-- Soft-deleted rows DO count: hiding a paid intent from a list does not give
-- the money back.
CREATE UNIQUE INDEX uq_payment_intents_one_live_per_order
    ON payment_intents (order_id)
    WHERE status IN ('PENDING', 'CHARGING', 'SUCCEEDED');

-- ---------------------------------------------------------------------------
-- 3. idempotency_keys: reserved before the charge, completed after it.
--
-- A key used to be written only after the charge, with its response. Two
-- requests carrying the same key could both miss it and both charge. Now the
-- key is written first, as RESERVED (no response yet), in the transaction
-- that moves the intent to CHARGING; a second request with that key finds it
-- and is refused while it is RESERVED, or served the stored response once it
-- is COMPLETED. Keys V4 stored all have their response, so they are COMPLETED.
-- ---------------------------------------------------------------------------
ALTER TABLE idempotency_keys ADD COLUMN state VARCHAR(16) NULL;
UPDATE idempotency_keys SET state = 'COMPLETED';
-- No default: every row says which state it is in, never by accident.
ALTER TABLE idempotency_keys ALTER COLUMN state SET NOT NULL;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_keys_state CHECK (state IN ('RESERVED', 'COMPLETED'));
ALTER TABLE idempotency_keys ALTER COLUMN response_json DROP NOT NULL;
-- A completed key is one that can be replayed, so it must hold what to replay.
ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_keys_completed_has_response
        CHECK (state <> 'COMPLETED' OR response_json IS NOT NULL);

-- ---------------------------------------------------------------------------
-- 4. The event log: shared/oriah-events/src/test/resources/db/oriah-events/
--    V1__outbox.sql then V2__processed_event.sql, copied verbatim below.
--    OutboxSchemaGuard and InboxSchemaGuard (PaymentSchemaTest) prove it.
-- ---------------------------------------------------------------------------

-- Reference SQL: the outbox and its relay cursor.
--
-- A producing service copies this file's statements into its own next-free
-- Flyway migration (the module ships no migration of its own, so each
-- service's version numbers stay its own). OutboxSchemaGuard, in this
-- module's test-jar, proves the copy is exact.
--
-- The outbox is the service's permanent log of what happened. Each row is
-- written in the same transaction as the change it describes, so the log
-- can never claim a change that rolled back, nor miss one that committed.

-- Refuses any change to a row of an append-only table. Shared by every
-- append-only table (CREATE OR REPLACE, so copying several reference files
-- into one migration is harmless). The message names the table and the
-- operation, so a refused statement says exactly what was refused.
CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TABLE outbox (
    -- The relay's position. Appends are serialised by an advisory lock, so
    -- ids are handed out in commit order and the relay can never skip a row
    -- that commits late. Keep the sequence's defaults (START 1, CACHE 1):
    -- the relay's cursor starts at 0 and sends only ids above it, and a
    -- larger cache lets each connection hold back a block of ids, so ids no
    -- longer follow commit order (OutboxSchemaGuard checks both).
    outbox_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The event's identity everywhere downstream; consumers dedupe on it.
    event_id       UUID NOT NULL UNIQUE,
    -- Which thing the event is about, e.g. ('order', '<order id>').
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    -- 1, 2, 3 ... per aggregate: lets a projection skip an older snapshot.
    seq            BIGINT NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    schema_version INT NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    -- When it happened in the real world. Never defaulted: a fact keyed in
    -- late carries its true, earlier date.
    occurred_at    TIMESTAMPTZ NOT NULL,
    -- When this row was written.
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Who did it: 'user:<keycloak subject>' or 'system:<name>'.
    actor          VARCHAR(128) NOT NULL,
    -- json, not jsonb: json keeps the text exactly as written, so the bytes
    -- stored are the bytes published.
    payload        JSON NOT NULL,
    UNIQUE (aggregate_type, aggregate_id, seq)
);

CREATE TRIGGER outbox_append_only_row
    BEFORE UPDATE OR DELETE ON outbox
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

-- A row trigger never fires for TRUNCATE, so TRUNCATE needs its own.
CREATE TRIGGER outbox_append_only_truncate
    BEFORE TRUNCATE ON outbox
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- How far the relay has published. Bookkeeping, not a fact, so it changes.
CREATE TABLE outbox_relay_cursor (
    relay          VARCHAR(64) PRIMARY KEY,
    last_outbox_id BIGINT NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Seeded here, in the same migration: the relay locks this row to take its
-- turn, so without it the relay would never publish anything.
INSERT INTO outbox_relay_cursor (relay, last_outbox_id, updated_at)
VALUES ('main', 0, now());

-- Reference SQL: processed_event, the record that a consumer has applied an
-- event.
--
-- Every consuming service copies this into its own next-free Flyway
-- migration, and so does every producer (it dedupes replay requests with
-- it). InboxSchemaGuard, in this module's test-jar, proves the copy.
--
-- Append-only: the marker is written in the same transaction as the
-- event's effect. Removing a marker would let the same event apply twice.

-- Same function as in the outbox reference file; CREATE OR REPLACE makes
-- copying both files into one migration harmless.
CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TABLE processed_event (
    consumer     VARCHAR(64) NOT NULL,
    event_id     UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TRIGGER processed_event_append_only_row
    BEFORE UPDATE OR DELETE ON processed_event
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER processed_event_append_only_truncate
    BEFORE TRUNCATE ON processed_event
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ---------------------------------------------------------------------------
-- 5. fake_gateway_charge: what the fake gateway has decided, by reference.
--
-- A real gateway remembers every charge it was asked for. The fake one used to
-- remember nothing, so it could not answer "what became of charge X?" and a
-- repeat of X would have been charged again. Now each reference gets exactly
-- one outcome, written in the gateway's own transaction:
--
--   * charge and void both insert and keep whatever was there first
--     (INSERT ... ON CONFLICT DO NOTHING), so when a charge and a void of the
--     same reference race, one outcome wins and both callers read it;
--   * a reference that was VOIDED stays voided: a charge arriving after the
--     void is answered VOIDED and captures nothing.
--
-- Append-only, with the outbox's own triggers: a decided outcome is never
-- rewritten or removed, so a void can never turn into a capture later.
-- ---------------------------------------------------------------------------
CREATE TABLE fake_gateway_charge (
    charge_reference  UUID PRIMARY KEY,
    outcome           VARCHAR(16) NOT NULL,
    -- The gateway's own id for a capture.
    gateway_reference VARCHAR(255) NULL,
    -- Why a charge was declined, in the gateway's words.
    reason            VARCHAR(255) NULL,
    -- What was charged. Empty only for a reference voided before any charge
    -- arrived, which has no amount.
    amount            NUMERIC(12, 2) NULL,
    currency          VARCHAR(3) NULL,
    -- When the gateway decided. This, not the moment payment heard about
    -- it, is when the payment happened.
    decided_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_fake_gateway_charge_outcome
        CHECK (outcome IN ('CAPTURED', 'DECLINED', 'VOIDED')),
    CONSTRAINT ck_fake_gateway_charge_captured_has_reference
        CHECK (outcome <> 'CAPTURED' OR gateway_reference IS NOT NULL),
    CONSTRAINT ck_fake_gateway_charge_declined_has_reason
        CHECK (outcome <> 'DECLINED' OR reason IS NOT NULL),
    -- A capture or a decline answers a charge, and a charge has an amount.
    CONSTRAINT ck_fake_gateway_charge_charge_has_amount
        CHECK (outcome = 'VOIDED' OR amount IS NOT NULL),
    -- Money never travels without its unit.
    CONSTRAINT ck_fake_gateway_charge_amount_has_currency
        CHECK ((amount IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_fake_gateway_charge_amount_positive
        CHECK (amount > 0)
);

CREATE TRIGGER fake_gateway_charge_append_only_row
    BEFORE UPDATE OR DELETE ON fake_gateway_charge
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER fake_gateway_charge_append_only_truncate
    BEFORE TRUNCATE ON fake_gateway_charge
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
