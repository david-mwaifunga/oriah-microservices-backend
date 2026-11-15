-- Phase 8 (slice 8.4a): the dispatch record. How a paid order was packed, sent and handed over, and what the courier charged Oriah.
--
-- Three steps, each recorded once per order with the date it TRUE happened (a late-keyed step is recorded at its true date; the 72-hour window only decides
-- who may key it):
--   PACKED      the order became FULFILLED (self-collect: ready to collect);
--   DISPATCHED  the courier is engaged and the parcel handed over (SHIPPED) -- the line for refunds: before it a cancellation refunds delivery, from it goods
--               only (CLIENT_DECISIONS 1.4). Carries the courier's reference and the FARE Oriah paid, when it paid one;
--   DELIVERED   delivered (self-collect: handed over, FULFILLED to DELIVERED).
--
-- The fare is a fact about the dispatch, not the delivery CHARGE the shopper paid (which never changes, Q24). It is written once; a mis-keyed fare is
-- corrected in finance (Phase 10), never here. Append-only like every record of a change to a sale. No foreign key to orders, as order_box_amendment has none.

CREATE TABLE order_dispatch_step (
    id              UUID PRIMARY KEY,
    order_id        UUID         NOT NULL,
    step            VARCHAR(12)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,                 -- when it happened (the keyed date)
    recorded_at     TIMESTAMPTZ  NOT NULL,                 -- when it was typed
    recorded_by     VARCHAR(128) NOT NULL,                 -- user:<keycloak subject>
    reference       VARCHAR(40),                           -- waybill, booking or bus receipt; DISPATCHED only
    fare_paid       NUMERIC(12, 2),                        -- what the courier charged Oriah; DISPATCHED only; K0.00 is a real answer
    backdate_reason VARCHAR(200),                          -- why it was keyed beyond the window, when it was
    CONSTRAINT order_dispatch_step_known CHECK (step IN ('PACKED', 'DISPATCHED', 'DELIVERED')),
    CONSTRAINT order_dispatch_step_once UNIQUE (order_id, step),
    CONSTRAINT order_dispatch_step_dispatch_only CHECK (step = 'DISPATCHED' OR (reference IS NULL AND fare_paid IS NULL)),
    CONSTRAINT order_dispatch_step_fare_not_negative CHECK (fare_paid IS NULL OR fare_paid >= 0),
    CONSTRAINT order_dispatch_step_reference_shape CHECK (reference IS NULL OR reference ~ '^[A-Za-z0-9 ./#-]{1,40}$'),
    CONSTRAINT order_dispatch_step_backdate_reason_length CHECK (backdate_reason IS NULL OR char_length(btrim(backdate_reason)) BETWEEN 5 AND 200)
);

CREATE FUNCTION order_dispatch_step_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_dispatch_step is append-only: % is not allowed', TG_OP USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER order_dispatch_step_append_only_row
    BEFORE UPDATE OR DELETE ON order_dispatch_step
    FOR EACH ROW EXECUTE FUNCTION order_dispatch_step_append_only();

CREATE TRIGGER order_dispatch_step_append_only_truncate
    BEFORE TRUNCATE ON order_dispatch_step
    FOR EACH STATEMENT EXECUTE FUNCTION order_dispatch_step_append_only();
