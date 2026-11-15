-- Phase 10 (finance): which rows of the stock ledger have been announced as stock.cost_recorded.
--
-- One row per announced movement, written in the SAME transaction as the event, so the start-up back-fill (which announces the rows that were written before the
-- event existed) and the live path can never both announce one. Not append-only: it is bookkeeping about the outbox, never a fact about stock.

CREATE TABLE stock_cost_event (
    movement_id BIGINT      PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
