-- Phase 5 -- stock carries its cost: the cost lot.
--
-- A lot IS stock with its cost stamped on it: what one slice of one consignment line cost per unit, and how many
-- of those units are still held. Inventory makes one lot per slice (at most two per line: a line lands at up to two
-- unit costs a ngwee apart), all sharing the receipt's received_at. Cost belongs to a lot, never to a product.
--
-- Stock that exists before this migration came from the retired "create a stock row with an on-hand count"
-- endpoint. It has no cost basis, and a cost cannot be invented, so those rows are removed: only developer seed
-- data existed, and a go-live database starts empty. From now on a stock row is created only by a received
-- consignment, which brings its lots with it.

DO $$
DECLARE
    n integer;
BEGIN
    SELECT count(*) INTO n FROM inventory;
    IF n > 0 THEN
        RAISE NOTICE 'V7 removes % stock row(s) that have no cost (seed data from the retired create endpoint)', n;
    END IF;
END
$$;

DELETE FROM inventory;

-- Goods or packaging, taken from the receipt that created the row; never changes.
ALTER TABLE inventory ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'SELLABLE';
ALTER TABLE inventory ADD CONSTRAINT ck_inventory_kind CHECK (kind IN ('SELLABLE', 'PACKAGING'));

CREATE TABLE cost_lot (
    id                    UUID PRIMARY KEY,
    variant_id            UUID NOT NULL,
    kind                  VARCHAR(12) NOT NULL,
    -- What the receipt said these were, as at the receipt: a bookkeeper who may read cost cannot read
    -- catalog's admin list, so the lot carries its own names.
    sku                   VARCHAR(64) NOT NULL,
    product_title         VARCHAR(255),
    variant_title         TEXT NOT NULL,
    consignment_id        UUID NOT NULL,
    consignment_reference VARCHAR(40) NOT NULL,
    consignment_line_id   UUID NOT NULL,
    -- Null for day-one (opening) stock: no supplier is known.
    supplier_id           UUID,
    supplier_name         VARCHAR(255),
    -- The FIFO key: (received_at, fifo_tiebreak, slice_position), oldest first. Every line of one receipt shares
    -- received_at; fifo_tiebreak is the line's position in its consignment (or, for an event written before that
    -- field existed, its outbox id: both rise with line order within a receipt).
    received_at           TIMESTAMPTZ NOT NULL,
    fifo_tiebreak         BIGINT NOT NULL,
    slice_position        INTEGER NOT NULL,
    -- 1 = as received; n = the lots an amendment made (the same slices, corrected).
    revision              INTEGER NOT NULL DEFAULT 1,
    qty_received          INTEGER NOT NULL,
    qty_remaining         INTEGER NOT NULL,
    -- Whole ngwee per unit; may be 0.00 only for an absurd input (a landed total smaller than its quantity in ngwee).
    unit_cost             NUMERIC(12, 2) NOT NULL,
    currency              VARCHAR(3) NOT NULL DEFAULT 'ZMW',
    -- Day-one stock is keyed from estimates; the flag follows the lot onto every ledger row it feeds.
    is_estimated          BOOLEAN NOT NULL,
    -- Set when an amendment replaced this lot with corrected ones; a replaced lot holds nothing.
    superseded_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cost_lot_variant FOREIGN KEY (variant_id) REFERENCES inventory (variant_id),
    CONSTRAINT ck_cost_lot_kind CHECK (kind IN ('SELLABLE', 'PACKAGING')),
    CONSTRAINT ck_cost_lot_slice CHECK (slice_position >= 1 AND revision >= 1),
    CONSTRAINT ck_cost_lot_qty CHECK (qty_received >= 1),
    CONSTRAINT ck_cost_lot_remaining CHECK (qty_remaining >= 0 AND qty_remaining <= qty_received),
    CONSTRAINT ck_cost_lot_unit_cost CHECK (unit_cost >= 0),
    CONSTRAINT ck_cost_lot_currency CHECK (currency = 'ZMW'),
    CONSTRAINT ck_cost_lot_superseded CHECK (superseded_at IS NULL OR qty_remaining = 0),
    CONSTRAINT uq_cost_lot_slice UNIQUE (consignment_line_id, revision, slice_position)
);

-- Oldest first, among the lots that are still current.
CREATE INDEX ix_cost_lot_fifo ON cost_lot (variant_id, received_at, fifo_tiebreak, slice_position)
    WHERE superseded_at IS NULL;
CREATE INDEX ix_cost_lot_line ON cost_lot (consignment_line_id);

-- Only what is left, and being replaced, may ever change on a lot. Its cost, size and identity never do, and a lot
-- is never deleted: a cost corrected later is a NEW lot, with the old one kept and marked replaced.
CREATE FUNCTION oriah_cost_lot_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'cost_lot rows are never deleted';
    END IF;
    IF (NEW.id, NEW.variant_id, NEW.kind, NEW.sku, NEW.product_title, NEW.variant_title, NEW.consignment_id,
        NEW.consignment_reference, NEW.consignment_line_id, NEW.supplier_id, NEW.supplier_name, NEW.received_at,
        NEW.fifo_tiebreak, NEW.slice_position, NEW.revision, NEW.qty_received, NEW.unit_cost, NEW.currency,
        NEW.is_estimated, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.variant_id, OLD.kind, OLD.sku, OLD.product_title, OLD.variant_title, OLD.consignment_id,
        OLD.consignment_reference, OLD.consignment_line_id, OLD.supplier_id, OLD.supplier_name, OLD.received_at,
        OLD.fifo_tiebreak, OLD.slice_position, OLD.revision, OLD.qty_received, OLD.unit_cost, OLD.currency,
        OLD.is_estimated, OLD.created_at) THEN
        RAISE EXCEPTION 'cost_lot: only qty_remaining and superseded_at may change';
    END IF;
    IF OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at THEN
        RAISE EXCEPTION 'cost_lot: a replaced lot stays replaced';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cost_lot_guard
    BEFORE UPDATE OR DELETE ON cost_lot
    FOR EACH ROW EXECUTE FUNCTION oriah_cost_lot_guard();
