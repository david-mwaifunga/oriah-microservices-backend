-- procurement V5: receiving a consignment -- the allocation that was recorded, and the
-- rules that keep a received consignment exactly as it was received.
--
-- When the goods land, the unit costs are worked out (the allocator, to the ngwee) and the
-- result is recorded here as REVISION 1. Those costs are the books: profit is computed from
-- them for years, and inventory's lots are built from the events that carry them. So:
--
--   * a RECEIVED consignment, its lines, its cost lines and what was typed for them cannot be
--     changed or deleted: the database refuses it, whatever the application does;
--   * a recorded allocation is APPEND-ONLY: not one row of it can be changed or deleted. A
--     correction is a new revision with a reason (Phase 5's amendment), never an edit.
--
-- The recorded inputs (JSONB) keep every figure the allocator was given, in whole ngwee, so the
-- result can be reproduced and audited from the record alone, even after a later revision.
--
-- Row triggers, not statement triggers: TRUNCATE is deliberately left to the test harness
-- (purchase orders are frozen the same way in V3).

CREATE TABLE allocation_revision (
    id                UUID PRIMARY KEY,
    consignment_id    UUID           NOT NULL,
    -- 1 is the receipt itself; later numbers are amendments and need a reason.
    revision          INTEGER        NOT NULL,
    reason            VARCHAR(200),
    -- Every input the allocator was given, in whole ngwee, and the shape that was costed.
    inputs            JSONB          NOT NULL,
    total_goods       NUMERIC(12, 2) NOT NULL,
    total_capitalized NUMERIC(12, 2) NOT NULL,
    total_period      NUMERIC(12, 2) NOT NULL,
    -- Goods plus capitalized costs: what the units recover, to the ngwee.
    total_landed      NUMERIC(12, 2) NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        VARCHAR(64),
    CONSTRAINT fk_allocation_revision_consignment FOREIGN KEY (consignment_id) REFERENCES consignment (id),
    CONSTRAINT uq_allocation_revision_number UNIQUE (consignment_id, revision),
    CONSTRAINT ck_allocation_revision_number CHECK (revision >= 1),
    CONSTRAINT ck_allocation_revision_reason CHECK (revision = 1 OR (reason IS NOT NULL AND reason <> ''))
);

-- What one consignment line came to in one revision.
CREATE TABLE allocation_line (
    id                  UUID PRIMARY KEY,
    revision_id         UUID           NOT NULL,
    consignment_line_id UUID           NOT NULL,
    goods_total         NUMERIC(12, 2) NOT NULL,
    -- The capitalized costs that were put on this line.
    shared_cost         NUMERIC(12, 2) NOT NULL,
    landed_total        NUMERIC(12, 2) NOT NULL,
    CONSTRAINT fk_allocation_line_revision FOREIGN KEY (revision_id) REFERENCES allocation_revision (id),
    CONSTRAINT fk_allocation_line_line FOREIGN KEY (consignment_line_id) REFERENCES consignment_line (id),
    CONSTRAINT uq_allocation_line_revision_line UNIQUE (revision_id, consignment_line_id)
);

-- The unit costs of a line: at most two, a ngwee apart, dearer first. Inventory makes one lot
-- per slice.
CREATE TABLE allocation_slice (
    allocation_line_id UUID           NOT NULL,
    position           INTEGER        NOT NULL,
    quantity           INTEGER        NOT NULL,
    unit_cost          NUMERIC(12, 2) NOT NULL,
    PRIMARY KEY (allocation_line_id, position),
    CONSTRAINT fk_allocation_slice_line FOREIGN KEY (allocation_line_id) REFERENCES allocation_line (id),
    CONSTRAINT ck_allocation_slice_quantity CHECK (quantity > 0),
    CONSTRAINT ck_allocation_slice_cost CHECK (unit_cost >= 0)
);

-- What each capitalized cost put on each line, so the sum can be shown line by line.
CREATE TABLE allocation_cost_share (
    revision_id         UUID           NOT NULL,
    cost_line_id        UUID           NOT NULL,
    consignment_line_id UUID           NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL,
    PRIMARY KEY (revision_id, cost_line_id, consignment_line_id),
    CONSTRAINT fk_allocation_cost_share_revision FOREIGN KEY (revision_id) REFERENCES allocation_revision (id),
    CONSTRAINT fk_allocation_cost_share_cost FOREIGN KEY (cost_line_id) REFERENCES cost_line (id),
    CONSTRAINT fk_allocation_cost_share_line FOREIGN KEY (consignment_line_id) REFERENCES consignment_line (id),
    CONSTRAINT ck_allocation_cost_share_amount CHECK (amount >= 0)
);

CREATE FUNCTION oriah_refuse_allocation_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'allocation records are append-only: % on % is refused; record a new revision instead',
        TG_OP, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER allocation_revision_append_only
    BEFORE UPDATE OR DELETE ON allocation_revision
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_allocation_change();
CREATE TRIGGER allocation_line_append_only
    BEFORE UPDATE OR DELETE ON allocation_line
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_allocation_change();
CREATE TRIGGER allocation_slice_append_only
    BEFORE UPDATE OR DELETE ON allocation_slice
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_allocation_change();
CREATE TRIGGER allocation_cost_share_append_only
    BEFORE UPDATE OR DELETE ON allocation_cost_share
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_allocation_change();

-- A RECEIVED consignment is history. Receiving writes the consignment's status last; after
-- that nothing may change it, its lines, its cost lines or what was typed for them.
--
-- A DRAFT that is deleted cascades to its lines, cost lines and shares: by then the parent row
-- is gone, so the lookup below finds no status and the cascade goes through.
CREATE FUNCTION oriah_freeze_received_consignment() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_status      VARCHAR(10);
    v_consignment UUID;
BEGIN
    -- A row never moves between consignments. The checks below judge an UPDATE by the parent a row
    -- HAS (or, for a share, the one it arrives at), so a move into a received consignment, or a
    -- share re-pointed out of one, would pass. Nothing in the application moves a row, so it is
    -- refused outright. (Nested IFs, not one expression: PL/pgSQL would still look up a column the
    -- other tables do not have.)
    IF TG_OP = 'UPDATE' THEN
        IF TG_TABLE_NAME = 'consignment_line' OR TG_TABLE_NAME = 'cost_line' THEN
            IF NEW.consignment_id IS DISTINCT FROM OLD.consignment_id THEN
                RAISE EXCEPTION 'a row never moves between consignments: UPDATE on % is refused', TG_TABLE_NAME;
            END IF;
        ELSIF TG_TABLE_NAME = 'cost_line_share' THEN
            IF NEW.cost_line_id IS DISTINCT FROM OLD.cost_line_id
               OR NEW.consignment_line_id IS DISTINCT FROM OLD.consignment_line_id THEN
                RAISE EXCEPTION 'a row never moves between consignments: UPDATE on % is refused', TG_TABLE_NAME;
            END IF;
        END IF;
    END IF;

    IF TG_TABLE_NAME = 'consignment' THEN
        -- Judged by the status the row HAS: the change that closes it (DRAFT -> RECEIVED) is the
        -- last change it may take.
        v_status := OLD.status;
    ELSE
        IF TG_TABLE_NAME = 'cost_line_share' THEN
            IF TG_OP = 'DELETE' THEN
                SELECT c.consignment_id INTO v_consignment FROM cost_line c WHERE c.id = OLD.cost_line_id;
            ELSE
                SELECT c.consignment_id INTO v_consignment FROM cost_line c WHERE c.id = NEW.cost_line_id;
            END IF;
        ELSIF TG_OP = 'INSERT' THEN
            v_consignment := NEW.consignment_id;
        ELSE
            v_consignment := OLD.consignment_id;
        END IF;
        SELECT k.status INTO v_status FROM consignment k WHERE k.id = v_consignment;
    END IF;

    IF v_status = 'RECEIVED' THEN
        RAISE EXCEPTION 'received consignment is frozen: % on % is refused', TG_OP, TG_TABLE_NAME;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER consignment_frozen_when_received
    BEFORE UPDATE OR DELETE ON consignment
    FOR EACH ROW EXECUTE FUNCTION oriah_freeze_received_consignment();
CREATE TRIGGER consignment_line_frozen_when_received
    BEFORE INSERT OR UPDATE OR DELETE ON consignment_line
    FOR EACH ROW EXECUTE FUNCTION oriah_freeze_received_consignment();
CREATE TRIGGER cost_line_frozen_when_received
    BEFORE INSERT OR UPDATE OR DELETE ON cost_line
    FOR EACH ROW EXECUTE FUNCTION oriah_freeze_received_consignment();
CREATE TRIGGER cost_line_share_frozen_when_received
    BEFORE INSERT OR UPDATE OR DELETE ON cost_line_share
    FOR EACH ROW EXECUTE FUNCTION oriah_freeze_received_consignment();

-- An order is attached only to a DRAFT consignment. The service refuses anything else, but the
-- freeze above judges a purchase order by ITS OWN status, so by hand an OPEN order could be
-- pointed at a received consignment, and no draft-only operation could ever release it again.
CREATE FUNCTION oriah_order_joins_only_a_draft() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_status VARCHAR(10);
BEGIN
    IF NEW.consignment_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR NEW.consignment_id IS DISTINCT FROM OLD.consignment_id) THEN
        SELECT k.status INTO v_status FROM consignment k WHERE k.id = NEW.consignment_id;
        -- A consignment that does not exist is the foreign key's to refuse, with its own name.
        IF v_status IS NOT NULL AND v_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'an order is attached only to a draft consignment: this one is %', v_status;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER purchase_order_joins_only_a_draft
    BEFORE INSERT OR UPDATE ON purchase_order
    FOR EACH ROW EXECUTE FUNCTION oriah_order_joins_only_a_draft();
