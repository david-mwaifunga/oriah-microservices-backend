-- Reference SQL: outbox_catch_up, consumer side, only in a service that uses
-- the CatchUp helper (a consumer added after producers have been running,
-- e.g. finance in Phase 10). Copied into that service's own next-free
-- migration; InboxSchemaGuard proves the copy.
--
-- One row per producer this consumer catches up from: the replay it asked
-- for (request_id), how far the replay has arrived without a gap
-- (replayed_through), and, once the producer's end-of-replay marker
-- arrives with no gap behind it, where the catch-up finished.

CREATE TABLE outbox_catch_up (
    producer          VARCHAR(64) PRIMARY KEY,
    request_id        UUID NOT NULL,
    replayed_through  BIGINT NOT NULL DEFAULT 0,
    completed_through BIGINT NULL,
    completed_at      TIMESTAMPTZ NULL
);
