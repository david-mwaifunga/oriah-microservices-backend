-- Phase 5 -- the stock ledger: every change to stock, with a reason from one closed list.
--
-- stock_action is the thing that happened (a receipt, a sale, a write-off, a give-away, a take-back, a cost
-- correction); stock_movement is what it did to each lot, one row per lot touched. Both are append-only: nothing
-- is edited or deleted, a correction is a later row that reverses and replaces.
--
-- The "COGS rows" the plan describes are the sale rows of this ledger: one per (sale line, lot drained), with the
-- quantity, the lot it came out of (so a return can go back to it at the cost it left at), whether its cost was
-- estimated, and the method and basis the cost was worked out by. A write-off or give-away is the same kind of
-- row with its own reason, so a report can tell them apart.
--
-- Per lot, sum(qty_delta) over its rows equals cost_lot.qty_remaining. A row that a re-cut has reversed is no
-- longer "live"; everything that asks what an action drained, what is outstanding, or which rows a return restores
-- reads through the live_movement view.

-- The same function as in the event log module's reference files; CREATE OR REPLACE makes repeating it harmless.
CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TABLE stock_action (
    -- Caller-supplied for a sale (the sale line) and for a write-off or take-back (a request id, so a retry is not a
    -- second action); the consignment line for a receipt; the event id for a correction. Used once, for one variant.
    id                 UUID PRIMARY KEY,
    -- Recording order, which is the order oldest-first selling is replayed in.
    seq                BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    variant_id         UUID NOT NULL,
    kind               VARCHAR(12) NOT NULL,
    reason             VARCHAR(40) NOT NULL,
    qty                INTEGER NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL,
    recorded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor              VARCHAR(100) NOT NULL,
    note               VARCHAR(200),
    -- A take-back names the write-off or give-away it takes back.
    reverses_action_id UUID,
    -- What was asked, so a retry with different fields is refused rather than treated as the same request.
    request_digest     VARCHAR(64),
    CONSTRAINT uq_stock_action_seq UNIQUE (seq),
    CONSTRAINT uq_stock_action_variant UNIQUE (variant_id, id),
    CONSTRAINT fk_stock_action_variant FOREIGN KEY (variant_id) REFERENCES inventory (variant_id),
    CONSTRAINT fk_stock_action_reverses FOREIGN KEY (variant_id, reverses_action_id) REFERENCES stock_action (variant_id, id),
    CONSTRAINT ck_stock_action_kind CHECK (kind IN ('RECEIPT', 'SALE', 'WRITE_OFF', 'GIVE_AWAY', 'TAKE_BACK', 'CORRECTION')),
    CONSTRAINT ck_stock_action_reason CHECK (reason IN (
        'CONSIGNMENT_RECEIVED', 'SALE', 'RETURN', 'WRITE_OFF_LOST', 'WRITE_OFF_STOLEN', 'WRITE_OFF_BROKEN',
        'GIVEN_AWAY_MODEL', 'GIVEN_AWAY_INFLUENCER', 'GIVEN_AWAY_GIFT', 'GIVEN_AWAY_PRIZE', 'TAKEN_BACK',
        'CANCELLED_BEFORE_DISPATCH', 'RELISTED_MARKED_DOWN', 'BOX_SWAPPED', 'COST_CORRECTED')),
    CONSTRAINT ck_stock_action_qty CHECK (qty > 0),
    CONSTRAINT ck_stock_action_take_back CHECK ((kind = 'TAKE_BACK') = (reverses_action_id IS NOT NULL))
);

CREATE TABLE stock_movement (
    -- Drain order: the order rows were written in.
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id           UUID NOT NULL,
    action_id            UUID NOT NULL,
    -- The SOURCE LOT: a return goes back to the lot it came out of, at the cost it left at.
    lot_id               UUID NOT NULL,
    -- The ECONOMIC reason, also on the rows a re-cut writes (a reversed K100 sale and its K120 replacement both say
    -- SALE, so a report by reason nets to K120). Only a lot transfer says COST_CORRECTED.
    reason               VARCHAR(40) NOT NULL,
    role                 VARCHAR(20) NOT NULL,
    -- Negative: units left a lot. Positive: units went into one.
    qty_delta            INTEGER NOT NULL,
    unit_cost            NUMERIC(12, 2) NOT NULL,
    currency             VARCHAR(3) NOT NULL DEFAULT 'ZMW',
    is_estimated         BOOLEAN NOT NULL,
    kind                 VARCHAR(12) NOT NULL,
    cost_method          VARCHAR(16) NOT NULL DEFAULT 'FIFO',
    cost_basis           VARCHAR(24) NOT NULL DEFAULT 'RECEIVED_AT',
    -- A take-back restores a drain row; a re-cut reverses a row that is being replaced.
    reverses_movement_id BIGINT,
    -- A re-cut's replacement names the first row it replaces.
    replaces_movement_id BIGINT,
    -- When it happened in the shop. A re-cut's rows keep the ACTION's date, never the day of the correction, so
    -- every month moves by exactly the cost of its own units. A lot transfer is dated the correction.
    occurred_at          TIMESTAMPTZ NOT NULL,
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor                VARCHAR(100) NOT NULL,
    note                 VARCHAR(200),
    CONSTRAINT fk_stock_movement_action FOREIGN KEY (variant_id, action_id) REFERENCES stock_action (variant_id, id),
    CONSTRAINT fk_stock_movement_lot FOREIGN KEY (lot_id) REFERENCES cost_lot (id),
    CONSTRAINT fk_stock_movement_reverses FOREIGN KEY (reverses_movement_id) REFERENCES stock_movement (id),
    CONSTRAINT fk_stock_movement_replaces FOREIGN KEY (replaces_movement_id) REFERENCES stock_movement (id),
    CONSTRAINT ck_stock_movement_reason CHECK (reason IN (
        'CONSIGNMENT_RECEIVED', 'SALE', 'RETURN', 'WRITE_OFF_LOST', 'WRITE_OFF_STOLEN', 'WRITE_OFF_BROKEN',
        'GIVEN_AWAY_MODEL', 'GIVEN_AWAY_INFLUENCER', 'GIVEN_AWAY_GIFT', 'GIVEN_AWAY_PRIZE', 'TAKEN_BACK',
        'CANCELLED_BEFORE_DISPATCH', 'RELISTED_MARKED_DOWN', 'BOX_SWAPPED', 'COST_CORRECTED')),
    CONSTRAINT ck_stock_movement_role CHECK (role IN ('ORIGINAL', 'RECUT_REVERSAL', 'RECUT_REPLACEMENT', 'LOT_OPEN', 'LOT_CLOSE')),
    CONSTRAINT ck_stock_movement_delta CHECK (qty_delta <> 0),
    CONSTRAINT ck_stock_movement_cost CHECK (unit_cost >= 0),
    CONSTRAINT ck_stock_movement_currency CHECK (currency = 'ZMW'),
    CONSTRAINT ck_stock_movement_kind CHECK (kind IN ('SELLABLE', 'PACKAGING')),
    CONSTRAINT ck_stock_movement_method CHECK (cost_method = 'FIFO' AND cost_basis = 'RECEIVED_AT'),
    -- A lot transfer (a lot closed, its units reopened at a corrected cost) is a correction and nothing else, and
    -- points at nothing; a reversal points at what it reverses; a replacement at what it replaces.
    CONSTRAINT ck_stock_movement_pointers CHECK (
        ((role IN ('LOT_OPEN', 'LOT_CLOSE')) = (reason = 'COST_CORRECTED'))
        AND (role NOT IN ('LOT_OPEN', 'LOT_CLOSE') OR (reverses_movement_id IS NULL AND replaces_movement_id IS NULL))
        AND (role <> 'RECUT_REVERSAL' OR reverses_movement_id IS NOT NULL)
        AND (role <> 'RECUT_REPLACEMENT' OR replaces_movement_id IS NOT NULL))
);

CREATE INDEX ix_stock_movement_lot ON stock_movement (lot_id);
CREATE INDEX ix_stock_movement_action ON stock_movement (action_id);
CREATE INDEX ix_stock_movement_variant ON stock_movement (variant_id, id);
-- A row can be reversed by a re-cut at most once, so a second amendment can never reverse what the first did.
CREATE UNIQUE INDEX uq_stock_movement_reversed_once ON stock_movement (reverses_movement_id) WHERE role = 'RECUT_REVERSAL';

CREATE TRIGGER stock_action_append_only
    BEFORE UPDATE OR DELETE ON stock_action
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER stock_movement_append_only
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

-- What is in force: an action's own rows and a re-cut's replacements, minus any a later re-cut has reversed.
-- Reversal rows and lot transfers are bookkeeping and never live.
CREATE VIEW live_movement AS
SELECT m.*
  FROM stock_movement m
 WHERE m.role IN ('ORIGINAL', 'RECUT_REPLACEMENT')
   AND NOT EXISTS (SELECT 1 FROM stock_movement r WHERE r.role = 'RECUT_REVERSAL' AND r.reverses_movement_id = m.id);
