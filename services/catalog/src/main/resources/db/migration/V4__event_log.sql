-- Catalog's event log (MVP_PLAN Phase 1).
--
-- Every product and price change is appended to `outbox` in the same
-- transaction as the change itself, and the relay publishes it to
-- catalog.product in the order it was written. `processed_event` records the
-- replay requests catalog has answered, so a redelivered request is not
-- replayed twice.
--
-- Below, verbatim and in order: shared/oriah-events reference
-- db/oriah-events/V1__outbox.sql and V2__processed_event.sql.
-- EventLogSchemaTest runs the module's OutboxSchemaGuard and InboxSchemaGuard
-- against this migration to prove the copy is exact.

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
