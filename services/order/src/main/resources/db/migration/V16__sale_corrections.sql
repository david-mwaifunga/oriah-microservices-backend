-- Phase 12 (Returns, refunds and exchanges): a paid sale is corrected, never edited (CLIENT_DECISIONS 1.1).
--
-- A correction is a record of its own that points back at the sale: a CANCELLATION of a paid order before it is dispatched (everything goes back and
-- everything paid is owed, delivery included), or a RETURN of pieces after dispatch or hand-over (the pieces' own price less their own discount is owed;
-- never delivery, never the box). orders and order_items are not changed: a paid row is never rewritten.
--
-- A correction is asked of inventory between two short transactions, so it is written REQUESTED first and becomes DONE once inventory has put the stock
-- back, or REFUSED when inventory proves it moved nothing. While it is REQUESTED nothing else may be done to the order.
--
-- No foreign key to orders: the correction is a fact about a sale and must outlive anything that might one day tidy that table.

CREATE TABLE order_correction (
    id                UUID           PRIMARY KEY,      -- random per attempt; it is inventory's sale-reversal id too
    order_id          UUID           NOT NULL,
    kind              VARCHAR(12)    NOT NULL,
    state             VARCHAR(10)    NOT NULL,
    reason_code       VARCHAR(24)    NOT NULL,
    note              VARCHAR(200),
    status_before     VARCHAR(20)    NOT NULL,         -- the order's status when the correction was recorded
    confirmation_id   UUID           NOT NULL,         -- the confirmed money that paid the sale
    receipt_number    VARCHAR(24)    NOT NULL,
    sale_arrived_at   TIMESTAMPTZ    NOT NULL,         -- the date the money arrived: the date of the sale
    occurred_at       TIMESTAMPTZ    NOT NULL,         -- cancellation: when it was cancelled; return: when the parcel came back
    backdate_reason   VARCHAR(200),                    -- why a return was keyed beyond the 72 hours, when it was
    goods_at_list     NUMERIC(12, 2) NOT NULL,
    discounts         NUMERIC(12, 2) NOT NULL,
    delivery_refunded NUMERIC(12, 2) NOT NULL,         -- the delivery charge actually paid; 0.00 on a return
    waiver_taken_back NUMERIC(12, 2) NOT NULL,         -- what delivery would have cost had it not been waived (a memo, never money); 0.00 on a return
    owed_gross        NUMERIC(12, 2) NOT NULL,         -- what the customer is owed, before any charge for sending it
    currency          VARCHAR(3)     NOT NULL,
    fee_bearer        VARCHAR(8)     NOT NULL,         -- who bears the charge for sending the refund: decided when recorded, never afterwards
    idempotency_key   VARCHAR(128),                    -- a return only
    request_hash      VARCHAR(64),                     -- a return only
    recorded_by       VARCHAR(128)   NOT NULL,         -- user:<keycloak subject>
    recorded_at       TIMESTAMPTZ    NOT NULL,
    attempts          INTEGER        NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    refused_code      VARCHAR(48),
    CONSTRAINT order_correction_kind_known   CHECK (kind IN ('CANCELLATION', 'RETURN')),
    CONSTRAINT order_correction_state_known  CHECK (state IN ('REQUESTED', 'DONE', 'REFUSED')),
    CONSTRAINT order_correction_done_timed   CHECK ((state = 'DONE') = (completed_at IS NOT NULL)),
    CONSTRAINT order_correction_refused_code CHECK ((state = 'REFUSED') = (refused_code IS NOT NULL)),
    CONSTRAINT order_correction_reason_known CHECK (
           (kind = 'CANCELLATION' AND reason_code IN ('CUSTOMER_ASKED', 'PIECE_UNAVAILABLE', 'SHOP_ERROR', 'OTHER'))
        OR (kind = 'RETURN' AND reason_code IN ('CHANGED_MIND', 'WRONG_SIZE', 'FAULTY', 'NOT_AS_DESCRIBED', 'WRONG_ITEM_SENT',
                                                'DAMAGED_IN_TRANSIT', 'NOT_DELIVERED', 'OTHER'))),
    CONSTRAINT order_correction_status_before CHECK (
           (kind = 'CANCELLATION' AND status_before IN ('PAID', 'FULFILLED'))
        OR (kind = 'RETURN' AND status_before IN ('SHIPPED', 'DELIVERED'))),
    CONSTRAINT order_correction_note_length  CHECK (note IS NULL OR char_length(btrim(note)) BETWEEN 5 AND 200),
    CONSTRAINT order_correction_other_says_why CHECK (reason_code <> 'OTHER' OR note IS NOT NULL),
    CONSTRAINT order_correction_backdate     CHECK (backdate_reason IS NULL OR (kind = 'RETURN' AND char_length(btrim(backdate_reason)) BETWEEN 5 AND 200)),
    CONSTRAINT order_correction_not_before_sale CHECK (occurred_at >= sale_arrived_at),
    CONSTRAINT order_correction_amounts      CHECK (goods_at_list >= 0 AND discounts >= 0 AND discounts <= goods_at_list
                                                    AND delivery_refunded >= 0 AND waiver_taken_back >= 0),
    CONSTRAINT order_correction_return_goods_only CHECK (kind = 'CANCELLATION' OR (delivery_refunded = 0 AND waiver_taken_back = 0)),
    CONSTRAINT order_correction_waiver_or_charge  CHECK (waiver_taken_back = 0 OR delivery_refunded = 0),
    CONSTRAINT order_correction_owed         CHECK (owed_gross = goods_at_list - discounts + delivery_refunded),
    CONSTRAINT order_correction_bearer_known CHECK (fee_bearer IN ('CUSTOMER', 'ORIAH')),
    CONSTRAINT order_correction_currency     CHECK (currency ~ '^[A-Z]{3}$'),
    -- Written so that a NULL never passes: "request_hash ~ ..." on a NULL is NULL, and a CHECK lets NULL through.
    CONSTRAINT order_correction_key_by_kind  CHECK (
           (kind = 'RETURN' AND idempotency_key IS NOT NULL AND request_hash IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
        OR (kind = 'CANCELLATION' AND idempotency_key IS NULL AND request_hash IS NULL)),
    CONSTRAINT order_correction_attempts     CHECK (attempts >= 0)
);

-- An order is cancelled once: a refused attempt took nothing and does not count.
CREATE UNIQUE INDEX uq_order_correction_cancellation ON order_correction (order_id) WHERE kind = 'CANCELLATION' AND state <> 'REFUSED';
-- One correction of an order is with inventory at a time.
CREATE UNIQUE INDEX uq_order_correction_in_flight    ON order_correction (order_id) WHERE state = 'REQUESTED';
CREATE UNIQUE INDEX uq_order_correction_key          ON order_correction (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX order_correction_due   ON order_correction (next_attempt_at) WHERE state = 'REQUESTED';
CREATE INDEX order_correction_order ON order_correction (order_id, recorded_at);

-- What was corrected and what is owed never change; only where the correction stands with inventory does, and only while it is in flight.
CREATE FUNCTION order_correction_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'state' - 'completed_at' - 'attempts' - 'next_attempt_at' - 'refused_code')
       IS DISTINCT FROM (to_jsonb(OLD) - 'state' - 'completed_at' - 'attempts' - 'next_attempt_at' - 'refused_code') THEN
        RAISE EXCEPTION 'order_correction: what was corrected and what is owed cannot be changed';
    END IF;
    IF OLD.state <> 'REQUESTED' THEN
        RAISE EXCEPTION 'order_correction: a finished or refused correction is final';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER order_correction_guard_update BEFORE UPDATE ON order_correction FOR EACH ROW EXECUTE FUNCTION order_correction_guard();
CREATE TRIGGER order_correction_no_delete    BEFORE DELETE ON order_correction FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER order_correction_no_truncate  BEFORE TRUNCATE ON order_correction FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

CREATE TABLE order_correction_line (
    correction_id      UUID           NOT NULL REFERENCES order_correction (id),
    sale_line_id       UUID           NOT NULL,             -- order_items.id, which is inventory's sale action id
    variant_id         UUID           NOT NULL,
    product_id         UUID           NOT NULL,
    quantity           INTEGER        NOT NULL,
    unit_price         NUMERIC(12, 2) NOT NULL,             -- the list price, copied
    discount_per_piece NUMERIC(12, 2) NOT NULL,             -- copied; a line with none recorded is refused before this, never read as zero
    promotion_id       UUID,
    box_line_id        UUID,                                -- a cancellation only: the box sale that stands when it is recorded (after any swap)
    box_variant_id     UUID,
    PRIMARY KEY (correction_id, sale_line_id),
    CONSTRAINT order_correction_line_quantity CHECK (quantity > 0),
    CONSTRAINT order_correction_line_price    CHECK (unit_price >= 0 AND discount_per_piece >= 0 AND discount_per_piece <= unit_price),
    CONSTRAINT order_correction_line_box      CHECK ((box_line_id IS NULL) = (box_variant_id IS NULL))
);

-- The wall: a line is a line of THIS order, carries exactly the sale's price, discount, product and promotion, and never more pieces than were sold
-- less every correction that was not refused.
CREATE FUNCTION order_correction_line_is_the_sale() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    item  order_items%ROWTYPE;
    corr  order_correction%ROWTYPE;
    taken INTEGER;
BEGIN
    SELECT * INTO corr FROM order_correction WHERE id = NEW.correction_id;
    SELECT * INTO item FROM order_items WHERE id = NEW.sale_line_id AND order_id = corr.order_id;
    IF item.id IS NULL THEN
        RAISE EXCEPTION 'order_correction_line: % is not a line of order %', NEW.sale_line_id, corr.order_id;
    END IF;
    IF item.unit_price <> NEW.unit_price OR item.discount_per_piece IS DISTINCT FROM NEW.discount_per_piece
       OR item.product_id IS DISTINCT FROM NEW.product_id OR item.variant_id <> NEW.variant_id
       OR item.promotion_id IS DISTINCT FROM NEW.promotion_id THEN
        RAISE EXCEPTION 'order_correction_line: a correction carries exactly what the sale line sold';
    END IF;
    SELECT COALESCE(sum(l.quantity), 0) INTO taken
      FROM order_correction_line l JOIN order_correction c ON c.id = l.correction_id
     WHERE l.sale_line_id = NEW.sale_line_id AND c.state <> 'REFUSED';
    IF taken + NEW.quantity > item.quantity THEN
        RAISE EXCEPTION 'order_correction_line: % sold, % already corrected', item.quantity, taken;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER order_correction_line_wall BEFORE INSERT ON order_correction_line FOR EACH ROW EXECUTE FUNCTION order_correction_line_is_the_sale();
CREATE TRIGGER order_correction_line_append_only_row BEFORE UPDATE OR DELETE ON order_correction_line FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER order_correction_line_append_only_truncate BEFORE TRUNCATE ON order_correction_line FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- One row per piece that came back. A piece waits (AWAITING) until someone looks at it: fine (back on sale), marked down (sold again on its own, cheaper)
-- or not sellable (written off). The decision is asked of inventory, so it is IN_PROGRESS until inventory has recorded it, then DONE.
CREATE TABLE order_return_piece (
    id                  UUID         PRIMARY KEY,           -- inventory's id for the piece too
    correction_id       UUID         NOT NULL,
    sale_line_id        UUID         NOT NULL,
    piece_no            INTEGER      NOT NULL,
    state               VARCHAR(12)  NOT NULL DEFAULT 'AWAITING',
    outcome             VARCHAR(12),
    markdown_variant_id UUID,
    note                VARCHAR(200),
    asked_by            VARCHAR(128),
    asked_at            TIMESTAMPTZ,
    assessed_at         TIMESTAMPTZ,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    FOREIGN KEY (correction_id, sale_line_id) REFERENCES order_correction_line (correction_id, sale_line_id),
    CONSTRAINT order_return_piece_once     UNIQUE (correction_id, sale_line_id, piece_no),
    CONSTRAINT order_return_piece_no       CHECK (piece_no >= 1),
    CONSTRAINT order_return_piece_state    CHECK (state IN ('AWAITING', 'IN_PROGRESS', 'DONE')),
    CONSTRAINT order_return_piece_outcome  CHECK (outcome IS NULL OR outcome IN ('FINE', 'MARKED_DOWN', 'UNSELLABLE')),
    CONSTRAINT order_return_piece_decided  CHECK ((state = 'AWAITING') = (outcome IS NULL)),
    CONSTRAINT order_return_piece_asked    CHECK ((outcome IS NULL) = (asked_by IS NULL) AND (outcome IS NULL) = (asked_at IS NULL)),
    CONSTRAINT order_return_piece_done     CHECK ((state = 'DONE') = (assessed_at IS NOT NULL)),
    CONSTRAINT order_return_piece_markdown CHECK (COALESCE(outcome = 'MARKED_DOWN', FALSE) = (markdown_variant_id IS NOT NULL)),
    CONSTRAINT order_return_piece_why      CHECK (COALESCE(outcome = 'UNSELLABLE', FALSE) = FALSE OR note IS NOT NULL),
    CONSTRAINT order_return_piece_note     CHECK (note IS NULL OR char_length(btrim(note)) BETWEEN 5 AND 200),
    CONSTRAINT order_return_piece_attempts CHECK (attempts >= 0)
);
CREATE INDEX order_return_piece_waiting ON order_return_piece (correction_id) WHERE state <> 'DONE';
CREATE INDEX order_return_piece_due     ON order_return_piece (next_attempt_at) WHERE state = 'IN_PROGRESS';

-- Which piece it is never changes. A decision is made whole (AWAITING to IN_PROGRESS), carried out (IN_PROGRESS to DONE) or taken back whole
-- (IN_PROGRESS to AWAITING, when inventory proves it did not record it); while it is in progress it cannot be swapped for another. DONE is final.
CREATE FUNCTION order_return_piece_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.correction_id <> OLD.correction_id OR NEW.sale_line_id <> OLD.sale_line_id OR NEW.piece_no <> OLD.piece_no THEN
        RAISE EXCEPTION 'order_return_piece: which piece this is cannot be changed';
    END IF;
    IF OLD.state = 'DONE' THEN
        RAISE EXCEPTION 'order_return_piece: an assessed piece is final';
    END IF;
    IF OLD.state = 'AWAITING' AND NEW.state = 'DONE' THEN
        RAISE EXCEPTION 'order_return_piece: a piece is decided, then asked of inventory, then done';
    END IF;
    IF OLD.state = 'IN_PROGRESS' AND NEW.state <> 'AWAITING'
       AND (NEW.outcome IS DISTINCT FROM OLD.outcome OR NEW.markdown_variant_id IS DISTINCT FROM OLD.markdown_variant_id
            OR NEW.note IS DISTINCT FROM OLD.note OR NEW.asked_by IS DISTINCT FROM OLD.asked_by OR NEW.asked_at IS DISTINCT FROM OLD.asked_at) THEN
        RAISE EXCEPTION 'order_return_piece: a decision that is with inventory cannot be changed, only finished or taken back whole';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER order_return_piece_guard_update BEFORE UPDATE ON order_return_piece FOR EACH ROW EXECUTE FUNCTION order_return_piece_guard();
CREATE TRIGGER order_return_piece_no_delete    BEFORE DELETE ON order_return_piece FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER order_return_piece_no_truncate  BEFORE TRUNCATE ON order_return_piece FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- An exchange is a return and a new order, linked: no money is netted between them (the refund is paid, the new order is paid). One link per return,
-- one per new order. The service refuses a return_id whose kind is not RETURN.
CREATE TABLE order_exchange (
    id           UUID         PRIMARY KEY,
    return_id    UUID         NOT NULL UNIQUE REFERENCES order_correction (id),
    new_order_id UUID         NOT NULL UNIQUE,
    linked_by    VARCHAR(128) NOT NULL,
    linked_at    TIMESTAMPTZ  NOT NULL
);
CREATE TRIGGER order_exchange_append_only_row BEFORE UPDATE OR DELETE ON order_exchange FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER order_exchange_append_only_truncate BEFORE TRUNCATE ON order_exchange FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
