-- Phase 10 (finance): which allocation revisions have been looked at for bills for the month (consignment.period_costs).
--
-- One row per (consignment, revision), written in the SAME transaction as the event it records (or as the decision that the revision has no bill to announce), so the
-- start-up back-fill, which announces the revisions that were recorded before the event existed, and the live path can never both announce one.
-- Not append-only: it is bookkeeping about the outbox, never a fact about a consignment.

CREATE TABLE period_cost_event (
    consignment_id UUID        NOT NULL,
    revision       INTEGER     NOT NULL CHECK (revision >= 1),
    emitted        BOOLEAN     NOT NULL,   -- false: the revision changed no bill for the month, so there was nothing to announce
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consignment_id, revision)
);
