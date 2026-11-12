-- procurement V3: purchase orders -- what Oriah placed with ONE supplier.
--
-- CLIENT_DECISIONS 4.1: "The order, when you place it. One line per item: which
-- piece, how many, and the total that left your bank for it -- never a converted
-- figure." The total is the all-in bank figure (3.1), so there is no bank-charge
-- column and no unit price: a unit price would be a division the books never made.
--
-- One supplier per order. A consignment (the shipment, V4) groups whole orders:
-- orders are placed with one supplier each, weeks apart, and a forwarder
-- consolidates several into one shipment under one courier bill.
--
-- consignment_id is set when an order is attached to a DRAFT consignment. It is a
-- plain column here and gets its foreign key in V4, when consignment exists.
--
-- Each line copies what the catalogue called the variant WHEN IT WAS ORDERED (sku,
-- titles, kind): history must not change when a product is renamed, and the
-- receipt event carries these names so inventory's Costs screen can show them
-- without asking anyone.
--
-- Same audit columns and optimistic-lock version as suppliers (V1).
CREATE TABLE purchase_order (
    id             UUID PRIMARY KEY,
    -- A number a person can quote on the phone or write on an invoice: PO-000123.
    ref_no         BIGINT       GENERATED ALWAYS AS IDENTITY UNIQUE,
    supplier_id    UUID         NOT NULL,
    placed_on      DATE         NOT NULL,
    note           VARCHAR(500),
    status         VARCHAR(10)  NOT NULL,
    consignment_id UUID,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    is_deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_purchase_order_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT ck_purchase_order_status CHECK (status IN ('OPEN', 'RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_purchase_order_supplier ON purchase_order (supplier_id);
CREATE INDEX idx_purchase_order_status ON purchase_order (status);
CREATE INDEX idx_purchase_order_consignment ON purchase_order (consignment_id);

CREATE TABLE purchase_order_line (
    id                UUID PRIMARY KEY,
    purchase_order_id UUID           NOT NULL,
    position          INTEGER        NOT NULL,
    variant_id        UUID           NOT NULL,
    quantity          INTEGER        NOT NULL,
    -- All-in, as the bank app shows it: NUMERIC(12,2) holds every ngwee exactly.
    total_paid        NUMERIC(12, 2) NOT NULL,
    -- The catalogue's name for it at the moment it was ordered.
    sku               VARCHAR(64)    NOT NULL,
    product_title     VARCHAR(200),
    -- TEXT, not VARCHAR: the catalogue composes it from the product title (up to 200) and one "axis value"
    -- pair per option axis (64 + 1 + 64 each), so it has no practical limit. Catalog stores it as TEXT.
    variant_title     TEXT           NOT NULL,
    kind              VARCHAR(10)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    is_deleted        BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_purchase_order_line_order FOREIGN KEY (purchase_order_id)
        REFERENCES purchase_order (id) ON DELETE CASCADE,
    -- DEFERRED to commit: replacing an order's lines deletes the old rows and inserts new
    -- ones numbered from 1 again, and Hibernate sends the inserts before the deletes. Checked
    -- at commit, the order of the statements inside the transaction does not matter.
    CONSTRAINT uq_purchase_order_line_position UNIQUE (purchase_order_id, position)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_purchase_order_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_purchase_order_line_total CHECK (total_paid > 0),
    CONSTRAINT ck_purchase_order_line_kind CHECK (kind IN ('SELLABLE', 'PACKAGING'))
);

CREATE INDEX idx_purchase_order_line_order ON purchase_order_line (purchase_order_id, position);
CREATE INDEX idx_purchase_order_line_variant ON purchase_order_line (variant_id);

-- A RECEIVED or CANCELLED order is history. Receiving writes the order's status last;
-- after that nothing may change the order or its lines. (An OPEN order that is
-- attached to a consignment is frozen by the service, which owns that rule.)
CREATE FUNCTION oriah_freeze_closed_purchase_order() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    order_status VARCHAR(10);
    order_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'purchase_order' THEN
        IF TG_OP = 'DELETE' THEN
            order_status := OLD.status;
        ELSE
            -- An UPDATE is judged by the status the row HAS; the change that closes
            -- it (OPEN -> RECEIVED or CANCELLED) is the last change it may take.
            order_status := OLD.status;
        END IF;
    ELSE
        -- A line never moves to another order: judged by the old order alone, a move INTO a closed
        -- order would pass, and a closed order's lines would change after the fact.
        IF TG_OP = 'UPDATE' AND NEW.purchase_order_id IS DISTINCT FROM OLD.purchase_order_id THEN
            RAISE EXCEPTION 'a row never moves between purchase orders: UPDATE on % is refused', TG_TABLE_NAME;
        END IF;
        IF TG_OP = 'INSERT' THEN
            order_id := NEW.purchase_order_id;
        ELSE
            order_id := OLD.purchase_order_id;
        END IF;
        SELECT status INTO order_status FROM purchase_order WHERE id = order_id;
    END IF;

    IF order_status IN ('RECEIVED', 'CANCELLED') THEN
        RAISE EXCEPTION 'purchase order is frozen: it is %, and % on % is refused', order_status, TG_OP, TG_TABLE_NAME;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER purchase_order_frozen_when_closed
    BEFORE UPDATE OR DELETE ON purchase_order
    FOR EACH ROW EXECUTE FUNCTION oriah_freeze_closed_purchase_order();

CREATE TRIGGER purchase_order_line_frozen_when_closed
    BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_line
    FOR EACH ROW EXECUTE FUNCTION oriah_freeze_closed_purchase_order();
