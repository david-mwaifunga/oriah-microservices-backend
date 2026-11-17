-- Phase 12: returns, refunds and exchanges. What inventory needs so that a piece can come back.
--
-- A paid order cancelled before it left: its pieces and boxes go back to the lots they came out of, at the cost they left at.
-- A parcel that came back: each piece goes back to its own lot too, but it is NOT for sale until somebody has looked at it. Then it is
-- released (fine), written off (not sellable), or moved to a listing of its own (sellable but marked: "returned, marked down"), backed
-- by a one-piece lot that keeps the cost of the lot it came from.

-- 1. Returned pieces waiting to be looked at: counted on hand, never free.
ALTER TABLE inventory ADD COLUMN quarantined INTEGER NOT NULL DEFAULT 0;
ALTER TABLE inventory ADD CONSTRAINT inventory_quarantined_nonnegative CHECK (quarantined >= 0);
-- A box that went out does not come back: only goods wait to be looked at.
ALTER TABLE inventory ADD CONSTRAINT inventory_quarantine_only_sellable CHECK (quarantined = 0 OR kind = 'SELLABLE');
ALTER TABLE inventory DROP CONSTRAINT inventory_committed_within_stock;
ALTER TABLE inventory ADD CONSTRAINT inventory_committed_within_stock CHECK (reserved + committed + quarantined <= on_hand);

-- 2. Three new kinds of action (each fits VARCHAR(12)), one new reason, and the pointer from an outcome to the returned piece it decides.
--    RELEASE      the returned piece is fine: ordinary stock again (no movement row: it was already in its lot).
--    RETURN_EXIT  the returned piece leaves its lot: written off as not sellable, or moved to a marked-down listing.
--    RELIST_IN    the other half of that move: the piece arrives in the one-piece lot of its marked-down listing.
ALTER TABLE stock_action DROP CONSTRAINT ck_stock_action_kind;
ALTER TABLE stock_action ADD CONSTRAINT ck_stock_action_kind CHECK (kind IN
    ('RECEIPT', 'SALE', 'WRITE_OFF', 'GIVE_AWAY', 'TAKE_BACK', 'CORRECTION', 'RELEASE', 'RETURN_EXIT', 'RELIST_IN'));
ALTER TABLE stock_action DROP CONSTRAINT ck_stock_action_reason;
ALTER TABLE stock_action ADD CONSTRAINT ck_stock_action_reason CHECK (reason IN (
    'CONSIGNMENT_RECEIVED', 'SALE', 'RETURN', 'WRITE_OFF_LOST', 'WRITE_OFF_STOLEN', 'WRITE_OFF_BROKEN',
    'GIVEN_AWAY_MODEL', 'GIVEN_AWAY_INFLUENCER', 'GIVEN_AWAY_GIFT', 'GIVEN_AWAY_PRIZE', 'TAKEN_BACK',
    'CANCELLED_BEFORE_DISPATCH', 'RELISTED_MARKED_DOWN', 'BOX_SWAPPED', 'COST_CORRECTED', 'WRITE_OFF_RETURNED'));
ALTER TABLE stock_movement DROP CONSTRAINT ck_stock_movement_reason;
ALTER TABLE stock_movement ADD CONSTRAINT ck_stock_movement_reason CHECK (reason IN (
    'CONSIGNMENT_RECEIVED', 'SALE', 'RETURN', 'WRITE_OFF_LOST', 'WRITE_OFF_STOLEN', 'WRITE_OFF_BROKEN',
    'GIVEN_AWAY_MODEL', 'GIVEN_AWAY_INFLUENCER', 'GIVEN_AWAY_GIFT', 'GIVEN_AWAY_PRIZE', 'TAKEN_BACK',
    'CANCELLED_BEFORE_DISPATCH', 'RELISTED_MARKED_DOWN', 'BOX_SWAPPED', 'COST_CORRECTED', 'WRITE_OFF_RETURNED'));

ALTER TABLE stock_action ADD COLUMN assesses_action_id UUID;
ALTER TABLE stock_action ADD CONSTRAINT fk_stock_action_assesses
    FOREIGN KEY (variant_id, assesses_action_id) REFERENCES stock_action (variant_id, id);
-- An outcome, and only an outcome, names the returned piece it decides. (kind is NOT NULL, so neither side of the = can be NULL.)
ALTER TABLE stock_action ADD CONSTRAINT ck_stock_action_assesses
    CHECK ((kind IN ('RELEASE', 'RETURN_EXIT')) = (assesses_action_id IS NOT NULL));
CREATE UNIQUE INDEX uq_stock_action_assessed_once ON stock_action (assesses_action_id) WHERE assesses_action_id IS NOT NULL;
-- Each new kind and reason only in the shape it has. (kind, reason and qty are NOT NULL: no arm of this can pass on a NULL.)
ALTER TABLE stock_action ADD CONSTRAINT ck_stock_action_return_steps CHECK (
        (reason <> 'RETURN' OR (kind IN ('TAKE_BACK', 'RELEASE') AND qty = 1))
    AND (kind <> 'RELEASE' OR reason = 'RETURN')
    AND (kind <> 'RETURN_EXIT' OR (reason IN ('WRITE_OFF_RETURNED', 'RELISTED_MARKED_DOWN') AND qty = 1))
    AND (kind <> 'RELIST_IN' OR (reason = 'RELISTED_MARKED_DOWN' AND qty = 1))
    AND (reason <> 'WRITE_OFF_RETURNED' OR kind = 'RETURN_EXIT')
    AND (reason <> 'RELISTED_MARKED_DOWN' OR kind IN ('RETURN_EXIT', 'RELIST_IN')));
-- ck_stock_action_take_back (V8) is unchanged: only a TAKE_BACK names reverses_action_id, so "how much was taken back" never counts
-- an outcome.

-- The database's own walls: never more taken back than the action moved; an outcome decides a returned piece and nothing else.
CREATE FUNCTION stock_action_walls() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    moved INTEGER;
    back  INTEGER;
BEGIN
    IF NEW.reverses_action_id IS NOT NULL THEN
        SELECT qty INTO moved FROM stock_action WHERE id = NEW.reverses_action_id;
        SELECT COALESCE(sum(qty), 0) INTO back FROM stock_action WHERE reverses_action_id = NEW.reverses_action_id;
        IF back + NEW.qty > moved THEN
            RAISE EXCEPTION 'stock_action: % of % already taken back', back, moved;
        END IF;
    END IF;
    IF NEW.assesses_action_id IS NOT NULL AND NOT EXISTS
       (SELECT 1 FROM stock_action WHERE id = NEW.assesses_action_id AND kind = 'TAKE_BACK' AND reason = 'RETURN') THEN
        RAISE EXCEPTION 'stock_action: an outcome decides a returned piece';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_action_walls_row BEFORE INSERT ON stock_action FOR EACH ROW EXECUTE FUNCTION stock_action_walls();

-- 3. The marked-down lot family: a one-piece lot that says which lot its piece came from, and which movement took it out.
ALTER TABLE cost_lot ADD COLUMN source_lot_id    UUID REFERENCES cost_lot (id);
ALTER TABLE cost_lot ADD COLUMN source_action_id UUID;
ALTER TABLE cost_lot ADD CONSTRAINT ck_cost_lot_child_whole CHECK ((source_lot_id IS NULL) = (source_action_id IS NULL));
ALTER TABLE cost_lot ADD CONSTRAINT ck_cost_lot_child_is_one CHECK (source_lot_id IS NULL
    OR (qty_received = 1 AND kind = 'SELLABLE' AND source_lot_id <> id));
-- A line has each slice of each revision once among its OWN lots; a marked-down lot shares its source's line and does not count.
ALTER TABLE cost_lot DROP CONSTRAINT uq_cost_lot_slice;
CREATE UNIQUE INDEX uq_cost_lot_slice ON cost_lot (consignment_line_id, revision, slice_position) WHERE source_lot_id IS NULL;
-- One marked-down lot per piece moved, per revision of its cost.
CREATE UNIQUE INDEX uq_cost_lot_child ON cost_lot (source_action_id, revision) WHERE source_action_id IS NOT NULL;
CREATE INDEX ix_cost_lot_source ON cost_lot (source_lot_id) WHERE source_lot_id IS NOT NULL;

-- The guard of V7, with the two new columns among what can never change.
CREATE OR REPLACE FUNCTION oriah_cost_lot_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'cost_lot rows are never deleted';
    END IF;
    IF (NEW.id, NEW.variant_id, NEW.kind, NEW.sku, NEW.product_title, NEW.variant_title, NEW.consignment_id,
        NEW.consignment_reference, NEW.consignment_line_id, NEW.supplier_id, NEW.supplier_name, NEW.received_at,
        NEW.fifo_tiebreak, NEW.slice_position, NEW.revision, NEW.qty_received, NEW.unit_cost, NEW.currency,
        NEW.is_estimated, NEW.created_at, NEW.source_lot_id, NEW.source_action_id)
       IS DISTINCT FROM
       (OLD.id, OLD.variant_id, OLD.kind, OLD.sku, OLD.product_title, OLD.variant_title, OLD.consignment_id,
        OLD.consignment_reference, OLD.consignment_line_id, OLD.supplier_id, OLD.supplier_name, OLD.received_at,
        OLD.fifo_tiebreak, OLD.slice_position, OLD.revision, OLD.qty_received, OLD.unit_cost, OLD.currency,
        OLD.is_estimated, OLD.created_at, OLD.source_lot_id, OLD.source_action_id) THEN
        RAISE EXCEPTION 'cost_lot: only qty_remaining and superseded_at may change';
    END IF;
    IF OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at THEN
        RAISE EXCEPTION 'cost_lot: a replaced lot stays replaced';
    END IF;
    RETURN NEW;
END;
$$;

-- 4. A sale reversed: one row per correction the order system asked for (its id is order's correction id).
CREATE TABLE sale_reversal (
    id             UUID         PRIMARY KEY,
    kind           VARCHAR(12)  NOT NULL,
    order_id       UUID         NOT NULL,
    -- Cancellation only: the consumed hold it undoes.
    reservation_id UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    -- What was asked, so the same id with another request is refused rather than treated as a repeat.
    request_digest VARCHAR(64)  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL,
    actor          VARCHAR(128) NOT NULL,
    CONSTRAINT sale_reversal_kind_known CHECK (kind IN ('CANCELLATION', 'RETURN')),
    CONSTRAINT sale_reversal_digest CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT sale_reversal_cancellation_names_hold CHECK ((kind = 'CANCELLATION') = (reservation_id IS NOT NULL))
);
-- A hold is undone once.
CREATE UNIQUE INDEX uq_sale_reversal_one_cancellation ON sale_reversal (reservation_id) WHERE reservation_id IS NOT NULL;
CREATE TRIGGER sale_reversal_no_update   BEFORE UPDATE ON sale_reversal FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_reversal_no_delete   BEFORE DELETE ON sale_reversal FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_reversal_no_truncate BEFORE TRUNCATE ON sale_reversal FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- One returned piece: put back (its id is the id of the TAKE_BACK action that put it back), then decided once.
CREATE TABLE returned_piece (
    piece_id          UUID         PRIMARY KEY,
    reversal_id       UUID         NOT NULL REFERENCES sale_reversal (id),
    sale_line_id      UUID         NOT NULL,
    variant_id        UUID         NOT NULL,
    restored_at       TIMESTAMPTZ  NOT NULL,
    outcome           VARCHAR(12),
    outcome_action_id UUID,
    child_variant_id  UUID,
    outcome_at        TIMESTAMPTZ,
    outcome_actor     VARCHAR(128),
    CONSTRAINT returned_piece_outcome_known CHECK (outcome IS NULL OR outcome IN ('RELEASED', 'WRITTEN_OFF', 'RELISTED')),
    -- An outcome comes whole: what, by which movement, when and by whom, or none of them.
    CONSTRAINT returned_piece_outcome_whole CHECK ((outcome IS NULL) = (outcome_action_id IS NULL)
        AND (outcome IS NULL) = (outcome_at IS NULL) AND (outcome IS NULL) = (outcome_actor IS NULL)),
    -- Relisted, and only relisted, names the new listing. (COALESCE: an undecided piece is "not relisted", never NULL.)
    CONSTRAINT returned_piece_child CHECK (COALESCE(outcome = 'RELISTED', FALSE) = (child_variant_id IS NOT NULL))
);
CREATE INDEX returned_piece_waiting ON returned_piece (variant_id) WHERE outcome IS NULL;

CREATE FUNCTION returned_piece_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.outcome IS NOT NULL THEN
        RAISE EXCEPTION 'returned_piece: a piece is decided once';
    END IF;
    IF (NEW.piece_id, NEW.reversal_id, NEW.sale_line_id, NEW.variant_id, NEW.restored_at)
       IS DISTINCT FROM (OLD.piece_id, OLD.reversal_id, OLD.sale_line_id, OLD.variant_id, OLD.restored_at) THEN
        RAISE EXCEPTION 'returned_piece: only its outcome may be written';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER returned_piece_guard_update BEFORE UPDATE ON returned_piece FOR EACH ROW EXECUTE FUNCTION returned_piece_guard();
CREATE TRIGGER returned_piece_no_delete    BEFORE DELETE ON returned_piece FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER returned_piece_no_truncate  BEFORE TRUNCATE ON returned_piece FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- A returned piece moved to its own marked-down listing: which piece, from which variant to which, by which two movements.
CREATE TABLE markdown_relist (
    piece_id          UUID         PRIMARY KEY REFERENCES returned_piece (piece_id),
    source_variant_id UUID         NOT NULL,
    child_variant_id  UUID         NOT NULL UNIQUE,
    exit_action_id    UUID         NOT NULL UNIQUE,
    in_action_id      UUID         NOT NULL UNIQUE,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    recorded_at       TIMESTAMPTZ  NOT NULL,
    actor             VARCHAR(128) NOT NULL,
    CONSTRAINT markdown_relist_two_variants CHECK (source_variant_id <> child_variant_id)
);
CREATE INDEX markdown_relist_source ON markdown_relist (source_variant_id);
CREATE TRIGGER markdown_relist_no_update   BEFORE UPDATE ON markdown_relist FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER markdown_relist_no_delete   BEFORE DELETE ON markdown_relist FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER markdown_relist_no_truncate BEFORE TRUNCATE ON markdown_relist FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- 5. Boxes of a cancelled order going back to the set-aside they came from.
ALTER TABLE box_commitment_change DROP CONSTRAINT box_commitment_change_kind_known;
ALTER TABLE box_commitment_change ADD CONSTRAINT box_commitment_change_kind_known
    CHECK (kind IN ('ALLOCATE', 'REPLACE', 'RELEASE', 'CONSUME', 'WRITE_OFF_CUT', 'RETURN'));

-- 6. What inventory knows of a variant gains its SKU: a marked-down listing's one-piece lot is labelled from here.
ALTER TABLE product_index ADD COLUMN sku VARCHAR(64);
