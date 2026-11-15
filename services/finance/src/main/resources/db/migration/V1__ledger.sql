-- Phase 10 (finance): the ledger, the expenses and taxes typed in by hand, and the closed lists they are chosen from.
--
-- Everything that moves money is an immutable ROW of ledger_entry: a sale's revenue, a discount, the cost of goods released, stock written off or given away, a fee, a bill
-- for the month, an expense, a tax. A correction is a reversing row plus a new one, never an update, so a month already reported can never lose a row. Amounts are
-- written in the account's natural direction (positive); a reversal is the same account with a negative amount. The profit a row adds is its amount times its
-- account's profit_sign.
--
-- Dates on a row, and why there are several:
--   occurred_at / effect_day  when it truly happened (the day of the sale, the day money arrived): what a month's profit is counted by. effect_day is a Lusaka calendar
--                             day, worked out in Java by BusinessCalendar; effect_month is derived from it by the database, so the two can never disagree.
--   recorded_at               when FINANCE learned it (its own clock, taken after the ledger lock is held): what "as of" means. A report as of T counts exactly the rows
--                             finance had at T, whenever the shop's other services say they happened, so an old report can be reproduced for ever.
--   source_recorded_at        when the producing service says it recorded it: shown, never used to cut a report.
--
-- Append-only: UPDATE, DELETE and TRUNCATE are refused by triggers. The database role the service connects with can still disable a trigger (every service's role owns its
-- schema), so this guards against mistakes, not against someone with the password; an issued month's snapshot (V2) is what shows a change after the fact.

CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

-- ------------------------------------------------------------------ the closed lists

CREATE TABLE ledger_account (
    code        VARCHAR(30) PRIMARY KEY,
    label       VARCHAR(80) NOT NULL,
    section     VARCHAR(10) NOT NULL CHECK (section IN ('REVENUE', 'COGS', 'WRITE_OFF', 'EXPENSE', 'TAX', 'MEMO')),
    -- +1 adds to profit, -1 takes from it, 0 is shown but never counted (held money, delivery given away).
    profit_sign SMALLINT    NOT NULL CHECK (profit_sign IN (-1, 0, 1))
);

INSERT INTO ledger_account (code, label, section, profit_sign) VALUES
    ('REVENUE_GOODS',        'Goods sold, at list price',                  'REVENUE',   1),
    ('DISCOUNTS',            'Discounts given',                            'REVENUE',  -1),
    ('REVENUE_DELIVERY',     'Delivery charges collected',                 'REVENUE',   1),
    ('COGS_GOODS',           'Cost of goods sold',                         'COGS',     -1),
    ('COGS_PACKAGING',       'Cost of packaging sold with the goods',      'COGS',     -1),
    ('STOCK_OUT',            'Stock written off or given away, at cost',   'WRITE_OFF',-1),
    ('EXPENSE',              'Expenses',                                   'EXPENSE',  -1),
    ('TAX_ENTERED',          'Turnover tax entered by hand',               'TAX',      -1),
    ('MEMO_HELD_MONEY',      'Money held for a customer (never revenue)',  'MEMO',      0),
    ('MEMO_DELIVERY_WAIVED', 'Delivery charges given up',                  'MEMO',      0);

-- What a write-off, a give-away or a take-back is booked under (the reason of the stock movement).
CREATE TABLE stock_out_reason (
    code VARCHAR(40) PRIMARY KEY
);

INSERT INTO stock_out_reason (code) VALUES
    ('WRITE_OFF_LOST'), ('WRITE_OFF_STOLEN'), ('WRITE_OFF_BROKEN'),
    ('GIVEN_AWAY_MODEL'), ('GIVEN_AWAY_INFLUENCER'), ('GIVEN_AWAY_GIFT'), ('GIVEN_AWAY_PRIZE'),
    ('TAKEN_BACK');

-- The headings an expense is entered under. A proposal: the owner has not fixed the list, and it grows by a migration, never by a free-text heading.
CREATE TABLE expense_category (
    code           VARCHAR(40)  PRIMARY KEY,
    label          VARCHAR(120) NOT NULL,
    hand_keyable   BOOLEAN      NOT NULL,   -- a person may type it in
    posted         BOOLEAN      NOT NULL,   -- the system posts rows under it from other services' records
    needs_order    BOOLEAN      NOT NULL DEFAULT FALSE,   -- a typed one must say which order it is for
    needs_provider BOOLEAN      NOT NULL DEFAULT FALSE,   -- a typed one must say which payment provider (mobile money or bank) it is for
    needs_confirmation BOOLEAN  NOT NULL DEFAULT FALSE,   -- a typed one must name the payment confirmation it adjusts
    CHECK (hand_keyable OR posted)
);

INSERT INTO expense_category (code, label, hand_keyable, posted, needs_order, needs_provider, needs_confirmation) VALUES
    ('RENT_UTILITIES',            'Rent and utilities',                                       TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('SALARIES_CASUAL_HELP',      'Salaries and casual help',                                 TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('AIRTIME_DATA_INTERNET',     'Airtime, data and internet',                               TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('MARKETING',                 'Marketing',                                                TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('SOFTWARE',                  'Software and subscriptions',                               TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('PACKING_CONSUMABLES',       'Packing supplies bought as an expense (not boxes held in stock)', TRUE, FALSE, FALSE, FALSE, FALSE),
    ('STALL_FEES',                'Market stall fees',                                        TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('DELIVERY_FUEL',             'Delivery fuel',                                            TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('DRIVER_PAY',                'Driver pay',                                               TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('BANK_MOBILE_ACCOUNT_FEES',  'Bank and mobile-money: monthly account fees only',         TRUE,  FALSE, FALSE, TRUE,  FALSE),
    ('COURIER_FARE',              'Courier fare (for one order)',                             TRUE,  TRUE,  TRUE,  FALSE, FALSE),
    ('OTHER',                     'Other',                                                    TRUE,  FALSE, FALSE, FALSE, FALSE),
    ('PAYMENT_CHARGES_ADJUSTMENT','Payment charge the system did not post, or posted wrongly', TRUE, FALSE, FALSE, FALSE, TRUE),
    ('PAYMENT_CHARGES',           'Payment charges (mobile money, card)',                     FALSE, TRUE,  FALSE, FALSE, FALSE),
    ('CONSIGNMENT_PERIOD_COST',   'Bills for the month on a consignment',                     FALSE, TRUE,  FALSE, FALSE, FALSE);

-- The taxes ON THE BUSINESS that may be entered by hand. Customs charges are NOT among them: they are paid at clearance and reach profit through the cost of goods, so
-- entering one here would count it twice.
CREATE TABLE tax_type (
    code  VARCHAR(30)  PRIMARY KEY,
    label VARCHAR(120) NOT NULL
);

INSERT INTO tax_type (code, label) VALUES ('TURNOVER_TAX', 'Turnover tax paid to ZRA');

CREATE TABLE tax_type_refused (
    code         VARCHAR(30)  PRIMARY KEY,
    plain_reason VARCHAR(300) NOT NULL
);

INSERT INTO tax_type_refused (code, plain_reason) VALUES
    ('DUTY',       'Duty is paid at clearance and is part of what the goods cost: it is entered on the consignment and reaches profit through the cost of goods. Entering it here would count it twice.'),
    ('SURTAX',     'Surtax is paid at clearance and is part of what the goods cost: it is entered on the consignment and reaches profit through the cost of goods. Entering it here would count it twice.'),
    ('IMPORT_VAT', 'Import VAT is paid at clearance and is part of what the goods cost: it is entered on the consignment and reaches profit through the cost of goods. Entering it here would count it twice.');

-- ------------------------------------------------------------------ the ledger

CREATE TABLE ledger_entry (
    -- Finance's own identity: only ever a handle, never an ordering the reports cut by (identity values are not commit-ordered).
    entry_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- What makes the row happen once: derived from the source's own key (a confirmation, a movement, a consignment revision), so a replayed event is a no-op.
    entry_key           VARCHAR(200) NOT NULL UNIQUE,
    account             VARCHAR(30)  NOT NULL REFERENCES ledger_account (code),
    category            VARCHAR(40)  REFERENCES expense_category (code),
    -- STOCK_OUT: the reason of the stock movement; a bill for the month: its cost type.
    reason              VARCHAR(40),
    amount              NUMERIC(12, 2) NOT NULL,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'ZMW' CHECK (currency = 'ZMW'),
    occurred_at         TIMESTAMPTZ  NOT NULL,
    effect_day          DATE         NOT NULL,
    -- The first day of effect_day's month, worked out by the database so the two cannot disagree.
    effect_month        DATE GENERATED ALWAYS AS (effect_day - (EXTRACT(DAY FROM effect_day)::int - 1)) STORED,
    recorded_at         TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    source_recorded_at  TIMESTAMPTZ,
    source              VARCHAR(30)  NOT NULL CHECK (source IN ('ORDER_PAID', 'PAYMENT_CONFIRMED', 'PAYMENT_VOIDED', 'PAYMENT_HELD', 'ORDER_SHIPPED',
                                                                  'STOCK_COST', 'PERIOD_COST', 'EXPENSE', 'TAX_ENTRY')),
    source_event_id     UUID,
    producer            VARCHAR(30),
    outbox_id           BIGINT,
    order_id            UUID,
    sale_line_id        UUID,
    confirmation_id     UUID,
    consignment_id      UUID,
    consignment_line_id UUID,
    variant_id          UUID,
    -- Frozen when the row is written, from what the event itself says: a product split can then only be reproduced, never move.
    product_id          UUID,
    stock_action_id     UUID,
    movement_id         BIGINT,
    promotion_id        UUID,
    stock_kind          VARCHAR(12)  CHECK (stock_kind IN ('SELLABLE', 'PACKAGING')),
    estimated           BOOLEAN      NOT NULL DEFAULT FALSE,
    reverses_entry_id   BIGINT       REFERENCES ledger_entry (entry_id),
    actor               VARCHAR(128) NOT NULL,
    -- A row of K0.00 is no row, except the cost of a piece that cost nothing: an estimated K0.00 lot that is sold must still be counted as an estimated sale.
    CONSTRAINT ck_ledger_amount_nonzero CHECK (amount <> 0 OR source = 'STOCK_COST'),
    CONSTRAINT ck_ledger_category CHECK ((account = 'EXPENSE') = (category IS NOT NULL)),
    CONSTRAINT ck_ledger_stock_out_reason CHECK (account <> 'STOCK_OUT' OR reason IS NOT NULL)
);

CREATE INDEX ix_ledger_month ON ledger_entry (effect_month, account);
CREATE INDEX ix_ledger_recorded ON ledger_entry (recorded_at);
CREATE INDEX ix_ledger_product ON ledger_entry (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX ix_ledger_order ON ledger_entry (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_ledger_confirmation ON ledger_entry (confirmation_id) WHERE confirmation_id IS NOT NULL;
-- A row can be reversed once: a second reversal of the same row is refused by the database, not by a check someone must remember.
CREATE UNIQUE INDEX uq_ledger_reversed_once ON ledger_entry (reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;

-- A stock-out row names a reason from the closed list, and a reversal negates exactly what it reverses: same account, category, currency and day.
CREATE OR REPLACE FUNCTION ledger_entry_check() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    original ledger_entry%ROWTYPE;
BEGIN
    IF NEW.account = 'STOCK_OUT' AND NOT EXISTS (SELECT 1 FROM stock_out_reason WHERE code = NEW.reason) THEN
        RAISE EXCEPTION 'ledger_entry: % is not a stock-out reason', NEW.reason;
    END IF;
    IF NEW.reverses_entry_id IS NOT NULL THEN
        SELECT * INTO original FROM ledger_entry WHERE entry_id = NEW.reverses_entry_id;
        IF original.account IS DISTINCT FROM NEW.account
           OR original.category IS DISTINCT FROM NEW.category
           OR original.currency IS DISTINCT FROM NEW.currency
           OR original.effect_day IS DISTINCT FROM NEW.effect_day
           OR original.amount <> -NEW.amount THEN
            RAISE EXCEPTION 'ledger_entry: a reversal negates exactly the row it reverses (same account, category, currency and day)';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_entry_check_row BEFORE INSERT ON ledger_entry FOR EACH ROW EXECUTE FUNCTION ledger_entry_check();
CREATE TRIGGER ledger_entry_append_only_row BEFORE UPDATE OR DELETE ON ledger_entry FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER ledger_entry_append_only_truncate BEFORE TRUNCATE ON ledger_entry FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------ expenses and taxes typed in by hand

-- One row per expense ledger row, typed or posted. Posted ones (a payment charge, a courier fare, a bill for the month) get a row too, so a reversal and a
-- correction have the same identity to point at whichever way the expense arrived.
CREATE TABLE expense (
    expense_id          UUID         PRIMARY KEY,
    ledger_entry_id     BIGINT       NOT NULL UNIQUE REFERENCES ledger_entry (entry_id),
    kind                VARCHAR(8)   NOT NULL CHECK (kind IN ('ADD', 'REVERSAL')),
    category            VARCHAR(40)  NOT NULL REFERENCES expense_category (code),
    description         VARCHAR(200) NOT NULL CHECK (char_length(btrim(description)) BETWEEN 3 AND 200),
    provider            VARCHAR(20),
    linked_order_id     UUID,
    linked_confirmation_id UUID,
    correction_group    UUID,
    reason              VARCHAR(200) CHECK (reason IS NULL OR char_length(btrim(reason)) BETWEEN 5 AND 200),
    in_issued_month     BOOLEAN      NOT NULL DEFAULT FALSE,
    origin              VARCHAR(6)   NOT NULL CHECK (origin IN ('KEYED', 'POSTED')),
    created_by          VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_expense_reversal_reason CHECK (kind <> 'REVERSAL' OR reason IS NOT NULL)
);

CREATE INDEX ix_expense_order ON expense (linked_order_id) WHERE linked_order_id IS NOT NULL;
CREATE INDEX ix_expense_group ON expense (correction_group) WHERE correction_group IS NOT NULL;

CREATE TRIGGER expense_append_only_row BEFORE UPDATE OR DELETE ON expense FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER expense_append_only_truncate BEFORE TRUNCATE ON expense FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- Turnover tax paid to ZRA, entered by hand: the month it is FOR, the amount and the day it was paid. Below the profit line, never among operating expenses.
-- A reversal stores the positive amount it reverses; the ledger row carries the sign.
CREATE TABLE tax_entry (
    tax_entry_id        UUID         PRIMARY KEY,
    ledger_entry_id     BIGINT       NOT NULL UNIQUE REFERENCES ledger_entry (entry_id),
    tax_type            VARCHAR(30)  NOT NULL REFERENCES tax_type (code),
    period_month        DATE         NOT NULL CHECK (EXTRACT(DAY FROM period_month) = 1),
    amount              NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    paid_on             DATE         NOT NULL,
    reference           VARCHAR(40),
    reverses_tax_entry_id UUID       REFERENCES tax_entry (tax_entry_id),
    correction_group    UUID,
    reason              VARCHAR(200) CHECK (reason IS NULL OR char_length(btrim(reason)) BETWEEN 5 AND 200),
    in_issued_month     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tax_reversal_reason CHECK (reverses_tax_entry_id IS NULL OR reason IS NOT NULL)
);

CREATE UNIQUE INDEX uq_tax_reversed_once ON tax_entry (reverses_tax_entry_id) WHERE reverses_tax_entry_id IS NOT NULL;

CREATE TRIGGER tax_entry_append_only_row BEFORE UPDATE OR DELETE ON tax_entry FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER tax_entry_append_only_truncate BEFORE TRUNCATE ON tax_entry FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- A write that is sent again (a double click, a retry after a timeout) is the same write: the caller's key, who it was, what was asked, and what was answered.
CREATE TABLE idempotency (
    created_by   VARCHAR(128) NOT NULL,
    key          VARCHAR(128) NOT NULL,
    method_path  VARCHAR(200) NOT NULL,
    fingerprint  VARCHAR(64)  NOT NULL,
    result_ref   JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (created_by, key)
);

CREATE TRIGGER idempotency_append_only_row BEFORE UPDATE OR DELETE ON idempotency FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER idempotency_append_only_truncate BEFORE TRUNCATE ON idempotency FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
