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

-- Reference SQL: processed_aggregate, for PROJECTION consumers only (a
-- consumer that keeps a copy of another service's state, e.g. search's
-- product index). Copied into the consumer's own next-free migration;
-- InboxSchemaGuard proves the copy.
--
-- One row per aggregate: the highest seq this consumer has applied. Each
-- event a projection reads is a full snapshot, so an older one arriving
-- late is skipped instead of overwriting newer state.
--
-- It changes (last_seq moves up) but is never deleted: when the projection
-- deletes its own row for a deleted product, this row stays behind as the
-- tombstone that stops a late, older snapshot bringing the product back.

CREATE TABLE processed_aggregate (
    consumer       VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    last_seq       BIGINT NOT NULL,
    PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);
