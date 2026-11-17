-- Phase 12: returns, refunds and cancellations of paid orders, in the books.
--
-- A corrected sale is booked as plain signed rows on the day of the correction (never as a reversal of the sale's own rows: the sale's
-- month does not move). What is owed to a customer and not yet paid is a memo; a payout clears it. The facts each row is derived from
-- are kept beside the ledger, insert-only, like the Phase 10 facts.

-- 1. The closed lists grow.
INSERT INTO ledger_account (code, label, section, profit_sign) VALUES
    ('MEMO_REFUNDS_OWED', 'Refunds owed to customers (not yet paid)', 'MEMO', 0);
-- A returned piece that cannot be sold again. (A returned piece moved to a marked-down listing recognises no cost: it is not here.)
INSERT INTO stock_out_reason (code) VALUES ('WRITE_OFF_RETURNED');
-- The sending charge a customer bore on a refund: posted by the system as a reduction of the charges Oriah paid, never typed.
INSERT INTO expense_category (code, label, hand_keyable, posted, needs_order, needs_provider, needs_confirmation) VALUES
    ('REFUND_FEE_RECOVERED', 'Sending charges on refunds that the customer bore (a reduction)', FALSE, TRUE, FALSE, FALSE, FALSE);

-- 2. Two inline CHECKs of V1 are widened. They were written without a name, so each is found by what it says (never by a guessed name),
--    dropped, and put back under a name of its own. LedgerSchemaTest asserts that exactly one constraint holds each list afterwards.
DO $$
DECLARE c TEXT;
BEGIN
    SELECT conname INTO STRICT c FROM pg_constraint WHERE conrelid = 'ledger_entry'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%ORDER_PAID%';
    EXECUTE format('ALTER TABLE ledger_entry DROP CONSTRAINT %I', c);
    SELECT conname INTO STRICT c FROM pg_constraint WHERE conrelid = 'expense'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%ADD%REVERSAL%';
    EXECUTE format('ALTER TABLE expense DROP CONSTRAINT %I', c);
END $$;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_source CHECK (source IN
    ('ORDER_PAID', 'PAYMENT_CONFIRMED', 'PAYMENT_VOIDED', 'PAYMENT_HELD', 'ORDER_SHIPPED', 'STOCK_COST', 'PERIOD_COST', 'EXPENSE',
     'TAX_ENTRY', 'SALE_CORRECTED', 'PAID_OUT', 'PAYOUT_VOIDED', 'REFUND_CLOSED'));
ALTER TABLE expense ADD CONSTRAINT ck_expense_kind CHECK (kind IN ('ADD', 'REVERSAL', 'RECOVERY'));

-- 3. A row says which correction or which payout it belongs to.
ALTER TABLE ledger_entry ADD COLUMN correction_id UUID;
ALTER TABLE ledger_entry ADD COLUMN payout_id UUID;
CREATE INDEX ix_ledger_correction ON ledger_entry (correction_id) WHERE correction_id IS NOT NULL;
CREATE INDEX ix_ledger_payout ON ledger_entry (payout_id) WHERE payout_id IS NOT NULL;
ALTER TABLE expense ADD COLUMN linked_payout_id UUID;

-- 4. The facts.
-- A paid order cancelled before it left, or pieces of it that came back: what the order system announced, frozen.
CREATE TABLE sale_correction (
    correction_id      UUID           PRIMARY KEY,
    kind               VARCHAR(12)    NOT NULL CHECK (kind IN ('CANCELLATION', 'RETURN')),
    order_id           UUID           NOT NULL,
    confirmation_id    UUID           NOT NULL,
    sale_arrived_at    TIMESTAMPTZ    NOT NULL,
    occurred_at        TIMESTAMPTZ    NOT NULL,
    goods_at_list      NUMERIC(12, 2) NOT NULL,
    discounts          NUMERIC(12, 2) NOT NULL,
    delivery_refunded  NUMERIC(12, 2) NOT NULL,
    waiver_taken_back  NUMERIC(12, 2) NOT NULL,
    owed_gross         NUMERIC(12, 2) NOT NULL,
    actor              VARCHAR(128)   NOT NULL,
    source_recorded_at TIMESTAMPTZ,
    source_event_id    UUID           NOT NULL,
    recorded_at        TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_sale_correction_order ON sale_correction (order_id);
CREATE INDEX ix_sale_correction_confirmation ON sale_correction (confirmation_id);

CREATE TABLE sale_correction_line (
    correction_id      UUID           NOT NULL REFERENCES sale_correction (correction_id),
    sale_line_id       UUID           NOT NULL,
    variant_id         UUID           NOT NULL,
    product_id         UUID           NOT NULL,
    quantity           INTEGER        NOT NULL CHECK (quantity > 0),
    unit_price         NUMERIC(12, 2) NOT NULL,
    discount_per_piece NUMERIC(12, 2) NOT NULL,
    promotion_id       UUID,
    PRIMARY KEY (correction_id, sale_line_id)
);
CREATE INDEX ix_sale_correction_line_sale_line ON sale_correction_line (sale_line_id);

-- What payment says is owed for a correction (its id is the correction's).
CREATE TABLE refund_due_fact (
    due_id          UUID           PRIMARY KEY,
    order_id        UUID           NOT NULL,
    confirmation_id UUID           NOT NULL,
    owed_gross      NUMERIC(12, 2) NOT NULL,
    status          VARCHAR(12)    NOT NULL CHECK (status IN ('OWED', 'IN_QUESTION')),
    owed_since      TIMESTAMPTZ    NOT NULL,
    source_event_id UUID           NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp()
);

-- Money that went out (a refund, or held money returned), and a payout that was taken back: one row each, never edited.
CREATE TABLE payout_fact (
    payout_id       UUID           NOT NULL,
    kind            VARCHAR(8)     NOT NULL CHECK (kind IN ('PAID_OUT', 'VOIDED')),
    purpose         VARCHAR(12)    NOT NULL CHECK (purpose IN ('REFUND', 'HELD_MONEY')),
    debt_id         UUID           NOT NULL,
    order_id        UUID           NOT NULL,
    confirmation_id UUID           NOT NULL,
    method          VARCHAR(16)    NOT NULL,
    gross           NUMERIC(12, 2) NOT NULL,
    customer_fee    NUMERIC(12, 2) NOT NULL,
    send_charge     NUMERIC(12, 2) NOT NULL,
    paid_at         TIMESTAMPTZ    NOT NULL,
    voided_at       TIMESTAMPTZ,
    actor           VARCHAR(128)   NOT NULL,
    source_event_id UUID           NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (payout_id, kind),
    -- kind is NOT NULL and "x IS NOT NULL" is never NULL, so neither side of the = can be NULL.
    CONSTRAINT payout_fact_voided_dated CHECK ((kind = 'VOIDED') = (voided_at IS NOT NULL))
);
CREATE INDEX ix_payout_fact_debt ON payout_fact (debt_id);
CREATE INDEX ix_payout_fact_confirmation ON payout_fact (confirmation_id);

-- A debt closed without a payout (the money it would have returned never arrived).
CREATE TABLE refund_close_fact (
    due_id          UUID           PRIMARY KEY,
    order_id        UUID           NOT NULL,
    confirmation_id UUID           NOT NULL,
    owed_gross      NUMERIC(12, 2) NOT NULL,
    closed_at       TIMESTAMPTZ    NOT NULL,
    actor           VARCHAR(128)   NOT NULL,
    source_event_id UUID           NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp()
);

-- All five are insert-only, like the Phase 10 facts.
CREATE TRIGGER sale_correction_append_only_row BEFORE UPDATE OR DELETE ON sale_correction FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_correction_append_only_truncate BEFORE TRUNCATE ON sale_correction FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_correction_line_append_only_row BEFORE UPDATE OR DELETE ON sale_correction_line FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_correction_line_append_only_truncate BEFORE TRUNCATE ON sale_correction_line FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER refund_due_fact_append_only_row BEFORE UPDATE OR DELETE ON refund_due_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER refund_due_fact_append_only_truncate BEFORE TRUNCATE ON refund_due_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER payout_fact_append_only_row BEFORE UPDATE OR DELETE ON payout_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER payout_fact_append_only_truncate BEFORE TRUNCATE ON payout_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER refund_close_fact_append_only_row BEFORE UPDATE OR DELETE ON refund_close_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER refund_close_fact_append_only_truncate BEFORE TRUNCATE ON refund_close_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
