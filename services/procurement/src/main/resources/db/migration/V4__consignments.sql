-- procurement V4: consignments -- one shipment, and the costs shared across it.
--
-- A consignment is built from WHOLE purchase orders: orders are placed with one supplier
-- each, weeks apart, and a forwarder consolidates several into one shipment under one
-- courier bill (client Q4-Q6: no part-shipments, no short or extra deliveries). Its lines
-- are copied from the orders' lines when an order is attached, and an attached order is
-- frozen (V3 + the service), so the copy cannot drift from what was ordered.
--
-- The OPENING consignment is the other kind: the stock the shop already held on day one,
-- typed in from the client's old book. It has no order and no supplier, it is always an
-- estimate, and a line cannot cost K0.
--
-- A consignment is a DRAFT while it is being built and RECEIVED once the goods have
-- landed. Everything about receiving (the allocation, the events, the rules that keep a
-- received consignment exactly as it was received) is V5.
--
-- Same audit columns and optimistic-lock version as V1 and V3.
CREATE TABLE consignment (
    id                  UUID PRIMARY KEY,
    -- A number a person can quote on the phone or write on an invoice: C-000045.
    ref_no              BIGINT       GENERATED ALWAYS AS IDENTITY UNIQUE,
    status              VARCHAR(10)  NOT NULL,
    is_opening          BOOLEAN      NOT NULL DEFAULT FALSE,
    -- The costs are approximations (the opening stock, or a shipment costed before the
    -- courier's final bill). Disclosed on the profit report (CLIENT_DECISIONS 11).
    is_estimated        BOOLEAN      NOT NULL DEFAULT FALSE,
    note                VARCHAR(500),
    -- What the forwarder's invoice says its charge covers. No default: recorded from the
    -- invoice, never assumed. DUTY_PAID: duty, surtax and import VAT are already inside
    -- the courier's charge, so a duty line of its own would count them twice.
    charges_basis       VARCHAR(12),
    declarant           VARCHAR(120),
    clearance_form      VARCHAR(4),
    zra_assessment_ref  VARCHAR(80),
    -- Nullable on purpose: unknown until someone says. CLIENT_DECISIONS 3.4.
    licensed_agent_used BOOLEAN,
    received_at         TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_consignment_status CHECK (status IN ('DRAFT', 'RECEIVED')),
    CONSTRAINT ck_consignment_opening_estimated CHECK (NOT is_opening OR is_estimated),
    CONSTRAINT ck_consignment_received_at CHECK ((status = 'RECEIVED') = (received_at IS NOT NULL)),
    CONSTRAINT ck_consignment_charges_basis CHECK (charges_basis IS NULL OR charges_basis IN ('DUTY_PAID', 'FREIGHT_ONLY')),
    CONSTRAINT ck_consignment_clearance_form CHECK (clearance_form IS NULL OR clearance_form IN ('CE6', 'CE20')),
    -- Lets a line point at (id, is_opening) so it cannot disagree with its consignment
    -- about being an opening line.
    CONSTRAINT uq_consignment_id_opening UNIQUE (id, is_opening)
);

CREATE INDEX idx_consignment_status ON consignment (status);

CREATE TABLE consignment_line (
    id                     UUID PRIMARY KEY,
    consignment_id         UUID           NOT NULL,
    -- Copied from the consignment and held equal to it by the composite key below.
    is_opening             BOOLEAN        NOT NULL,
    position               INTEGER        NOT NULL,
    -- Who the goods were bought from, and where in the books that was written down. All
    -- three are NULL on an opening line: typed in, no supplier, no order.
    supplier_id            UUID,
    purchase_order_id      UUID,
    purchase_order_line_id UUID,
    variant_id             UUID           NOT NULL,
    -- The catalogue's names when the line was ordered (or typed): history does not change
    -- when a product is renamed, and the receipt event carries them.
    sku                    VARCHAR(64)    NOT NULL,
    product_title          VARCHAR(200),
    variant_title          VARCHAR(300)   NOT NULL,
    kind                   VARCHAR(10)    NOT NULL,
    quantity               INTEGER        NOT NULL,
    -- All-in, as the bank app shows it. Never a unit price times a quantity.
    goods_total            NUMERIC(12, 2) NOT NULL,
    -- What the courier billed on, for the whole line, in whole grams. Needed only when a
    -- cost is shared by weight.
    chargeable_weight_grams BIGINT,
    origin_country         VARCHAR(2),
    hs_code                VARCHAR(14),
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by             VARCHAR(64),
    updated_by             VARCHAR(64),
    is_deleted             BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_consignment_line_consignment FOREIGN KEY (consignment_id, is_opening)
        REFERENCES consignment (id, is_opening) ON DELETE CASCADE,
    CONSTRAINT fk_consignment_line_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_consignment_line_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_consignment_line_order_line FOREIGN KEY (purchase_order_line_id) REFERENCES purchase_order_line (id),
    -- A purchase order line is in at most one consignment line.
    CONSTRAINT uq_consignment_line_po_line UNIQUE (purchase_order_line_id),
    -- DEFERRED to commit: attaching or detaching an order renumbers the lines.
    CONSTRAINT uq_consignment_line_position UNIQUE (consignment_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_consignment_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_consignment_line_goods CHECK (goods_total >= 0),
    CONSTRAINT ck_consignment_line_kind CHECK (kind IN ('SELLABLE', 'PACKAGING')),
    CONSTRAINT ck_consignment_line_weight CHECK (chargeable_weight_grams IS NULL OR chargeable_weight_grams >= 0),
    CONSTRAINT ck_consignment_line_origin_country CHECK (origin_country IS NULL OR origin_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_consignment_line_hs_code CHECK (hs_code IS NULL OR hs_code ~ '^[0-9.]{4,14}$'),
    CONSTRAINT ck_consignment_line_origin CHECK (
        (is_opening AND supplier_id IS NULL AND purchase_order_id IS NULL AND purchase_order_line_id IS NULL)
        OR (NOT is_opening AND supplier_id IS NOT NULL AND purchase_order_id IS NOT NULL
            AND purchase_order_line_id IS NOT NULL)),
    -- The client approximates every opening piece from the old book: a K0 cost is refused.
    CONSTRAINT ck_consignment_line_opening_cost CHECK (NOT is_opening OR goods_total > 0)
);

CREATE INDEX idx_consignment_line_consignment ON consignment_line (consignment_id, position);
CREATE INDEX idx_consignment_line_order ON consignment_line (purchase_order_id);
CREATE INDEX idx_consignment_line_variant ON consignment_line (variant_id);

-- One shared cost: what the courier charged, or a duty, with the way it is shared.
--
-- The cost types are a CLOSED list. A bank or transfer charge is not on it, and must never
-- be: the supplier price on a purchase order already is the total that left the bank
-- (CLIENT_DECISIONS 3.1), so a separate line would count it twice.
CREATE TABLE cost_line (
    id             UUID PRIMARY KEY,
    consignment_id UUID           NOT NULL,
    position       INTEGER        NOT NULL,
    cost_type      VARCHAR(20)    NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    -- How it is shared between the lines. DIRECT: typed per line (cost_line_share), which is
    -- how a ZRA assessment arrives.
    basis          VARCHAR(10)    NOT NULL,
    -- CAPITALIZE rides on the goods into the unit costs. PERIOD is a bill for the month,
    -- shown here and left out of the unit costs.
    treatment      VARCHAR(10)    NOT NULL,
    -- When the cost was incurred, if not the day of receipt. Matters for a PERIOD cost: the
    -- month it belongs to.
    incurred_on    DATE,
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    is_deleted     BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_cost_line_consignment FOREIGN KEY (consignment_id) REFERENCES consignment (id) ON DELETE CASCADE,
    -- DEFERRED to commit: replacing a consignment's cost lines renumbers them from 1.
    CONSTRAINT uq_cost_line_position UNIQUE (consignment_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_cost_line_type CHECK (cost_type IN ('COURIER_INBOUND', 'FREIGHT', 'INSURANCE', 'PORT_INCIDENTALS',
        'INBOUND_TRANSPORT', 'AGENT_FEE', 'DUTY', 'SURTAX', 'IMPORT_VAT')),
    CONSTRAINT ck_cost_line_amount CHECK (amount >= 0),
    CONSTRAINT ck_cost_line_basis CHECK (basis IN ('UNITS', 'VALUE', 'WEIGHT', 'DIRECT')),
    CONSTRAINT ck_cost_line_treatment CHECK (treatment IN ('CAPITALIZE', 'PERIOD'))
);

CREATE INDEX idx_cost_line_consignment ON cost_line (consignment_id, position);

-- What a person typed for one line of one cost (basis DIRECT, or an override of the
-- computed split). Must cover every line and add up to the cost exactly: the service
-- checks that, because the lines it covers change as orders are attached and detached.
CREATE TABLE cost_line_share (
    cost_line_id        UUID           NOT NULL,
    consignment_line_id UUID           NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL,
    PRIMARY KEY (cost_line_id, consignment_line_id),
    CONSTRAINT fk_cost_line_share_cost_line FOREIGN KEY (cost_line_id) REFERENCES cost_line (id) ON DELETE CASCADE,
    CONSTRAINT fk_cost_line_share_line FOREIGN KEY (consignment_line_id) REFERENCES consignment_line (id) ON DELETE CASCADE,
    CONSTRAINT ck_cost_line_share_amount CHECK (amount >= 0)
);

-- The order side of the link V3 left open. NO cascade: deleting a consignment that still
-- has orders pointing at it is refused, and the service detaches them first.
ALTER TABLE purchase_order
    ADD CONSTRAINT fk_purchase_order_consignment FOREIGN KEY (consignment_id) REFERENCES consignment (id);
