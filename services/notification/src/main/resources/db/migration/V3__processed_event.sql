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
