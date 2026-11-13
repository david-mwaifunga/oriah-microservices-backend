-- Phase 6: boxes set aside for a product (CLIENT_DECISIONS 4.2).
--
-- A packaging row now carries three numbers: on_hand (held), committed (promised to products) and reserved (held by unpaid orders);
-- what is FREE is on_hand - committed - reserved. Setting boxes aside is a PROMISE, not a movement: it reserves a quantity and never
-- a lot, so it touches no cost lot and the oldest lot's cost still goes with the sale.
--
-- Each product's set-aside is a record of its own (one per product, on its default box), so the right quantity is let go when the
-- product is unpublished, deleted or boxed differently, and a box write-off can name the products whose boxes it cut into. What
-- inventory knows of each product (its kind, whether it is on sale, its default box, how often it was unpublished) comes from
-- catalog's snapshots, kept in product_state.

CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

ALTER TABLE inventory ADD COLUMN committed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE inventory ADD CONSTRAINT inventory_committed_nonnegative CHECK (committed >= 0);
-- What is set aside and what is reserved can never exceed what is held: free is never below zero.
ALTER TABLE inventory ADD CONSTRAINT inventory_committed_within_stock CHECK (reserved + committed <= on_hand);
-- Only packaging is set aside for products.
ALTER TABLE inventory ADD CONSTRAINT inventory_committed_only_packaging CHECK (committed = 0 OR kind = 'PACKAGING');

-- What inventory knows of each product, from catalog's newest snapshot applied. An absent kind in a snapshot reads as SELLABLE.
CREATE TABLE product_state (
    product_id                   UUID PRIMARY KEY,
    kind                         VARCHAR(12)  NOT NULL,
    status                       VARCHAR(12)  NOT NULL,
    default_packaging_variant_id UUID,
    -- Only ever goes up: every unpublish adds one, so a consumer that missed or reordered snapshots can still tell that one happened.
    unpublish_count              INTEGER      NOT NULL DEFAULT 0,
    deleted                      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT product_state_kind_known CHECK (kind IN ('SELLABLE', 'PACKAGING')),
    CONSTRAINT product_state_unpublish_count_nonnegative CHECK (unpublish_count >= 0)
);

-- One record per product: the boxes set aside for it, on its default box. A record is deleted when its boxes are let go or used up
-- (the history below keeps what happened); it is never left at zero.
CREATE TABLE box_commitment (
    id              UUID PRIMARY KEY,
    product_id      UUID         NOT NULL,
    box_variant_id  UUID         NOT NULL,
    quantity        INTEGER      NOT NULL,
    -- The product's unpublish count when this was set aside. If the product's count has gone up since, it was taken off sale after.
    unpublish_count INTEGER      NOT NULL,
    committed_at    TIMESTAMPTZ  NOT NULL,
    committed_by    VARCHAR(128) NOT NULL,
    CONSTRAINT box_commitment_product_id_key UNIQUE (product_id),
    CONSTRAINT box_commitment_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX box_commitment_box ON box_commitment (box_variant_id, committed_at, id);

-- Everything done to any product's boxes, append-only.
CREATE TABLE box_commitment_change (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commitment_id  UUID         NOT NULL,
    product_id     UUID         NOT NULL,
    box_variant_id UUID         NOT NULL,
    kind           VARCHAR(16)  NOT NULL,
    qty_delta      INTEGER      NOT NULL,
    quantity_after INTEGER      NOT NULL,
    -- The stock action that caused it (a sale, a write-off) or the product event (an unpublish, a delete), when there was one.
    action_id      UUID,
    event_id       UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    actor          VARCHAR(128) NOT NULL,
    CONSTRAINT box_commitment_change_kind_known CHECK (kind IN ('ALLOCATE', 'REPLACE', 'RELEASE', 'CONSUME', 'WRITE_OFF_CUT')),
    CONSTRAINT box_commitment_change_after_nonnegative CHECK (quantity_after >= 0)
);

CREATE INDEX box_commitment_change_product ON box_commitment_change (product_id, id);

CREATE TRIGGER box_commitment_change_append_only_row
    BEFORE UPDATE OR DELETE ON box_commitment_change
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

-- A row trigger never fires for TRUNCATE, so TRUNCATE needs its own.
CREATE TRIGGER box_commitment_change_append_only_truncate
    BEFORE TRUNCATE ON box_commitment_change
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
