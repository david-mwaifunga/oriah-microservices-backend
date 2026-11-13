-- Phase 7 (slice 7.5): an order is a hold on pieces for a shopper who has not paid yet, and order OWNS the sale.
--
-- It took a hold at inventory (reservation id, when it lapses), it may carry the payment CLAIM the shopper submitted (how they say they paid), it
-- remembers every confirmed payment it was told about and what it did with it (order_settlement: the saga's memory and the staff's "stuck" queue), and
-- once money settles it, it carries the confirmation that paid it, the date the money arrived and the receipt number. The quote's fingerprint and the
-- Idempotency-Key make a placement happen once.
--
-- EVERY new column is NULLABLE or defaulted. An order placed before this migration is LEGACY: no reservation, no hold. It stays readable, and money
-- confirmed for it is HELD for the customer (never settled: there is nothing to consume).
--
-- No foreign key from order_settlement to orders: staff may confirm money for an order nobody knows (a mistyped id), and that money must be recorded and
-- held, not refused. The event log's own tables (outbox, processed_event) are not touched.

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- The order
-- ------------------------------------------------------------------------------------------------------------------------------------------
ALTER TABLE orders ADD COLUMN reservation_id          UUID;          -- the hold at inventory (minted by order before it is taken)
ALTER TABLE orders ADD COLUMN hold_state              VARCHAR(12);   -- PENDING (placed, inventory not yet asked or answered), HELD, RELEASED, CONSUMED
ALTER TABLE orders ADD COLUMN held_at                 TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN hold_expires_at         TIMESTAMPTZ;   -- as inventory last said
ALTER TABLE orders ADD COLUMN hold_extension_pending  BOOLEAN NOT NULL DEFAULT false;   -- a claim was submitted and the stretch of the hold is not yet confirmed
ALTER TABLE orders ADD COLUMN quote_digest            VARCHAR(64);   -- the fingerprint of the price the shopper was shown
ALTER TABLE orders ADD COLUMN idempotency_key         VARCHAR(128);
ALTER TABLE orders ADD COLUMN delivery_town_id        UUID;
ALTER TABLE orders ADD COLUMN delivery_payer          VARCHAR(16);   -- ORIAH_COLLECTS, or RECIPIENT_PAYS (the courier is paid on arrival: K0 on this order)
-- The payment claim: what the shopper SAID they paid with. Not money. Staff confirm money, or reject the claim.
ALTER TABLE orders ADD COLUMN claim_id                UUID;
ALTER TABLE orders ADD COLUMN claim_method            VARCHAR(16);
ALTER TABLE orders ADD COLUMN claim_reference         VARCHAR(64);   -- in its one spelling
ALTER TABLE orders ADD COLUMN claim_submitted_at      TIMESTAMPTZ;
-- A confirmed payment is being applied to this order (the settlement saga): its own actions answer 409 until it ends.
ALTER TABLE orders ADD COLUMN settling_confirmation_id UUID;
-- What paid the sale.
ALTER TABLE orders ADD COLUMN paid_confirmation_id    UUID;
ALTER TABLE orders ADD COLUMN paid_at                 TIMESTAMPTZ;   -- the day the money ARRIVED: the date of the sale
ALTER TABLE orders ADD COLUMN receipt_number          VARCHAR(24);   -- ours, never a tax invoice

ALTER TABLE orders ADD CONSTRAINT orders_hold_state_known CHECK (hold_state IS NULL OR hold_state IN ('PENDING', 'HELD', 'RELEASED', 'CONSUMED'));
ALTER TABLE orders ADD CONSTRAINT orders_claim_whole CHECK (
    (claim_id IS NULL) = (claim_method IS NULL) AND (claim_id IS NULL) = (claim_reference IS NULL) AND (claim_id IS NULL) = (claim_submitted_at IS NULL));
ALTER TABLE orders ADD CONSTRAINT orders_claim_method_known CHECK (claim_method IS NULL OR claim_method IN ('MTN', 'AIRTEL', 'ZAMTEL', 'BANK_TRANSFER'));
ALTER TABLE orders ADD CONSTRAINT orders_delivery_payer_known CHECK (delivery_payer IS NULL OR delivery_payer IN ('ORIAH_COLLECTS', 'RECIPIENT_PAYS'));
-- A paid order carries the confirmation that paid it and the date together; a receipt number only goes with them. (A legacy paid order carries none.)
ALTER TABLE orders ADD CONSTRAINT orders_paid_whole CHECK ((paid_confirmation_id IS NULL) = (paid_at IS NULL) AND (receipt_number IS NULL OR paid_confirmation_id IS NOT NULL));
ALTER TABLE orders ADD CONSTRAINT orders_receipt_number_key UNIQUE (receipt_number);

-- One live order per bag: the same shopper and the same quote cannot hold the pieces twice while the first is CREATED or PAYMENT_SUBMITTED.
CREATE UNIQUE INDEX uq_orders_live_bag ON orders (keycloak_id, quote_digest)
    WHERE quote_digest IS NOT NULL AND status IN ('CREATED', 'PAYMENT_SUBMITTED');
-- One placement per Idempotency-Key and shopper.
CREATE UNIQUE INDEX uq_orders_idempotency ON orders (keycloak_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- The hold job: holds due to lapse, and placements that have not heard from inventory.
CREATE INDEX orders_hold_due ON orders (hold_expires_at) WHERE hold_state = 'HELD' AND status IN ('CREATED', 'PAYMENT_SUBMITTED');
CREATE INDEX orders_hold_pending ON orders (placed_at) WHERE hold_state = 'PENDING';

CREATE SEQUENCE order_receipt_seq START WITH 1;

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- The line: what a promotion took off each piece, which promotion, the product (inventory's reserve needs it) and the box it goes out in.
-- order_items.id is the SALE LINE id inventory knows the line by; box_line_id is the box's own sale-line id.
-- ------------------------------------------------------------------------------------------------------------------------------------------
ALTER TABLE order_items ADD COLUMN product_id         UUID;
ALTER TABLE order_items ADD COLUMN discount_per_piece NUMERIC(12, 2);
ALTER TABLE order_items ADD COLUMN promotion_id       UUID;
ALTER TABLE order_items ADD COLUMN box_variant_id     UUID;
ALTER TABLE order_items ADD COLUMN box_line_id        UUID;
ALTER TABLE order_items ADD CONSTRAINT order_items_discount_check CHECK (discount_per_piece IS NULL OR discount_per_piece >= 0);

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- The settlement saga's memory: one row per confirmed payment order was told about, and what it did with it. Also the staff's queue of money that is
-- stuck. The money's own figures are kept here so the saga can finish after a crash, and so order.paid can carry them.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE order_settlement (
    id               UUID PRIMARY KEY,
    order_id         UUID           NOT NULL,                 -- no foreign key: money may be confirmed for an order nobody knows
    confirmation_id  UUID           NOT NULL UNIQUE,          -- the same announcement twice is the same settlement
    state            VARCHAR(12)    NOT NULL,
    reason           VARCHAR(24),
    method           VARCHAR(16)    NOT NULL,
    reference        VARCHAR(64),
    gross            NUMERIC(12, 2) NOT NULL,
    fee              NUMERIC(12, 2) NOT NULL,
    net              NUMERIC(12, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    arrived_at       TIMESTAMPTZ    NOT NULL,                 -- the date of the sale
    confirmed_by     VARCHAR(128),
    reservation_id   UUID,                                    -- the hold THIS attempt consumes (minted and kept, so a retry uses the same one)
    attempts         INTEGER        NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    deferred_end     VARCHAR(8),                              -- a lapse or a rejection that arrived while this was in flight: applied when it ends unsettled
    received_at      TIMESTAMPTZ    NOT NULL,
    settled_at       TIMESTAMPTZ,
    -- Payment voided this confirmation (staff cancelled money that had no outcome yet). Set whatever state the settlement is in: before it was applied,
    -- the sale is never settled with it (reason VOIDED, no event: payment already holds the fact); after, the sale is flagged for staff (the pieces
    -- cannot be un-sold) and payment marks the confirmation SETTLED_AFTER_VOID.
    voided_at        TIMESTAMPTZ,
    CONSTRAINT order_settlement_state_known CHECK (state IN ('RECEIVED', 'CONSUMING', 'BLOCKED_BOX', 'DONE', 'UNSETTLED')),
    CONSTRAINT order_settlement_reason_known CHECK (reason IS NULL OR reason IN ('WRONG_AMOUNT', 'ORDER_UNKNOWN', 'ORDER_CANCELLED', 'ALREADY_PAID', 'PIECE_SOLD', 'VOIDED')),
    CONSTRAINT order_settlement_unsettled_has_reason CHECK (state <> 'UNSETTLED' OR reason IS NOT NULL),
    CONSTRAINT order_settlement_deferred_end_known CHECK (deferred_end IS NULL OR deferred_end IN ('EXPIRED', 'REJECTED')),
    CONSTRAINT order_settlement_gross_positive CHECK (gross > 0),
    CONSTRAINT order_settlement_attempts_nonnegative CHECK (attempts >= 0)
);

-- An order has one LIVE settlement at a time (UNSETTLED ones do not count: money that could not settle is recorded and held, and another may follow).
CREATE UNIQUE INDEX uq_order_settlement_live ON order_settlement (order_id) WHERE state IN ('RECEIVED', 'CONSUMING', 'DONE', 'BLOCKED_BOX');
-- The retry job and the staff's stuck queue.
CREATE INDEX order_settlement_due ON order_settlement (next_attempt_at) WHERE state IN ('RECEIVED', 'CONSUMING', 'BLOCKED_BOX');
