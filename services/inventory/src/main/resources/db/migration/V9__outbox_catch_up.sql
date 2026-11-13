-- Phase 5 -- outbox_catch_up: inventory is a consumer that arrived after its producer (procurement) had been
-- running, so it asks procurement for the whole history of what it published (replay) and remembers how far the
-- replay has got. Copied from the event log module's reference (db/oriah-events/V4__outbox_catch_up.sql);
-- InboxSchemaGuard proves the copy.
--
-- One row per producer this consumer catches up from: the replay it asked for (request_id), how far the replay
-- has arrived without a gap (replayed_through), and, once the producer's end-of-replay marker arrives with no gap
-- behind it, where the catch-up finished.

CREATE TABLE outbox_catch_up (
    producer          VARCHAR(64) PRIMARY KEY,
    request_id        UUID NOT NULL,
    replayed_through  BIGINT NOT NULL DEFAULT 0,
    completed_through BIGINT NULL,
    completed_at      TIMESTAMPTZ NULL
);
