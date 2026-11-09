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
