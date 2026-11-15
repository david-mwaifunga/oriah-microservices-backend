-- Phase 8 (slice 8.1): a box swap that has been asked of inventory and is not yet recorded here.
--
-- A swap is asked of inventory between two short transactions, with no database connection held while inventory answers. For that window, and for as long
-- as it takes to finish a swap whose answer never came, the order carries this record: ONE per order (the primary key), written when the swap is planned and
-- deleted only when the swap is recorded (order_box_amendment written, the line carrying the new box) or when inventory refuses in a way that proves it
-- moved nothing. It never expires: a swap that was asked and never answered may have happened, and a parcel must never leave with one box while the
-- books say another, so the order cannot be shipped, and no OTHER swap can start, until this one is resumed (the same request again) and recorded.
--
-- Everything in it is what a repeat of the request needs: the amendment id inventory knows the swap by, the line, the box asked for, and the reason.
-- No foreign key to orders, as order_box_amendment has none; and it is NOT append-only (it is a marker, deleted when its swap is recorded): the swap
-- itself is kept for ever in order_box_amendment.

CREATE TABLE order_box_swap_inflight (
    order_id           UUID PRIMARY KEY,                       -- one swap in flight per order
    amendment_id       UUID         NOT NULL,                  -- the id shared with inventory: a repeat asks inventory for exactly the same swap
    item_id            UUID         NOT NULL,                  -- the order line whose box changes
    new_box_variant_id UUID         NOT NULL,
    reason             VARCHAR(200) NOT NULL,
    requested_by       VARCHAR(128) NOT NULL,                  -- user:<keycloak subject> of the member of staff who asked
    started_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT order_box_swap_inflight_reason_length CHECK (char_length(btrim(reason)) BETWEEN 10 AND 200)
);
