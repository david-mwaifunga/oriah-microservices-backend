-- Phase 12: money that goes back to customers.
--
-- A refund is never typed: order announces what a correction of a paid sale owes, payment keeps ONE debt per correction (under the
-- correction's own id), and a payout pays exactly a debt. Held money is paid back the same way. Every wall is in the database, so no
-- code path, test double or forgotten check can pay more than is owed, pay a debt twice, or pay one whose money is in question.

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- What is owed for one correction of a paid sale.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE refund_due (
    -- Order's correction id: one debt per correction, for ever.
    id                UUID           PRIMARY KEY,
    kind              VARCHAR(12)    NOT NULL,
    order_id          UUID           NOT NULL,
    -- The money that paid the sale: what is owed comes out of it, never more than it.
    confirmation_id   UUID           NOT NULL REFERENCES payment_confirmation (id),
    owed_gross        NUMERIC(12, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    -- Who bears the provider's charge for sending the money back, as order decided it from the reason.
    fee_bearer        VARCHAR(8)     NOT NULL,
    -- When the correction happened: a payout is never dated before it.
    owed_since        TIMESTAMPTZ    NOT NULL,
    source_event_id   UUID           NOT NULL UNIQUE,
    created_at        TIMESTAMPTZ    NOT NULL,
    status            VARCHAR(12)    NOT NULL,
    paid_money_out_id UUID,
    CONSTRAINT refund_due_kind_known     CHECK (kind IN ('CANCELLATION', 'RETURN')),
    CONSTRAINT refund_due_owed_positive  CHECK (owed_gross > 0),
    CONSTRAINT refund_due_currency       CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT refund_due_bearer_known   CHECK (fee_bearer IN ('CUSTOMER', 'ORIAH')),
    -- IN_QUESTION: the money record was cancelled after it paid the sale, and nobody has said yet whether the money arrived.
    CONSTRAINT refund_due_status_known   CHECK (status IN ('OWED', 'IN_QUESTION', 'PAID', 'CLOSED')),
    -- (status is NOT NULL and "x IS NOT NULL" is never NULL, so neither side of the = can be NULL.)
    CONSTRAINT refund_due_paid_names_payout CHECK ((status = 'PAID') = (paid_money_out_id IS NOT NULL))
);
CREATE INDEX refund_due_open ON refund_due (owed_since) WHERE status IN ('OWED', 'IN_QUESTION');
CREATE INDEX refund_due_confirmation ON refund_due (confirmation_id);
CREATE INDEX refund_due_order ON refund_due (order_id);

-- The owner's word, once, on a debt in question: did the money arrive?
CREATE TABLE refund_due_check (
    id              UUID         PRIMARY KEY,
    refund_due_id   UUID         NOT NULL UNIQUE REFERENCES refund_due (id),
    money_arrived   BOOLEAN      NOT NULL,
    reason          VARCHAR(200) NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 200),
    checked_by      VARCHAR(128) NOT NULL,
    checked_by_name VARCHAR(100),
    checked_at      TIMESTAMPTZ  NOT NULL
);
CREATE TRIGGER refund_due_check_append_only_row BEFORE UPDATE OR DELETE ON refund_due_check FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER refund_due_check_no_truncate BEFORE TRUNCATE ON refund_due_check FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- Money that went out: a refund of a debt, or held money returned.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE money_out (
    id                  UUID           PRIMARY KEY,
    purpose             VARCHAR(12)    NOT NULL,
    refund_due_id       UUID           REFERENCES refund_due (id),
    held_money_id       UUID           REFERENCES held_money (id),
    -- The debt this payout stands for; NULL once the payout was cancelled. One standing payout per debt.
    live_for            UUID,
    order_id            UUID           NOT NULL,
    confirmation_id     UUID           NOT NULL REFERENCES payment_confirmation (id),
    -- CANCELLATION or RETURN for a refund; why the money was held, for held money.
    cause               VARCHAR(24)    NOT NULL,
    -- Copied from the debt, never typed.
    gross               NUMERIC(12, 2) NOT NULL,
    -- The part of the provider's charge the customer bore: it comes off what they receive.
    customer_fee        NUMERIC(12, 2) NOT NULL,
    net                 NUMERIC(12, 2) GENERATED ALWAYS AS (gross - customer_fee) STORED,
    -- What the provider charged Oriah to send the money.
    send_charge         NUMERIC(12, 2) NOT NULL,
    fee_bearer          VARCHAR(8)     NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    method              VARCHAR(16)    NOT NULL,
    reference           VARCHAR(64),
    reference_key       VARCHAR(81),
    paid_at             TIMESTAMPTZ    NOT NULL,
    backdate_reason     VARCHAR(200),
    paid_by             VARCHAR(128)   NOT NULL,
    paid_by_name        VARCHAR(100),
    recorded_at         TIMESTAMPTZ    NOT NULL,
    idempotency_key     VARCHAR(128)   NOT NULL UNIQUE,
    request_fingerprint VARCHAR(64)    NOT NULL,
    CONSTRAINT money_out_purpose_known  CHECK (purpose IN ('REFUND', 'HELD_MONEY')),
    -- (purpose is NOT NULL and "x IS NOT NULL" is never NULL.)
    CONSTRAINT money_out_names_one_debt CHECK ((purpose = 'REFUND') = (refund_due_id IS NOT NULL)
                                              AND (purpose = 'HELD_MONEY') = (held_money_id IS NOT NULL)),
    CONSTRAINT money_out_live_is_its_debt CHECK (live_for IS NULL OR live_for = COALESCE(refund_due_id, held_money_id)),
    CONSTRAINT money_out_gross_positive CHECK (gross > 0),
    CONSTRAINT money_out_send_charge    CHECK (send_charge >= 0),
    -- Never more than the provider charged, and never the whole amount: the customer always receives something.
    CONSTRAINT money_out_customer_fee   CHECK (customer_fee >= 0 AND customer_fee <= send_charge AND customer_fee < gross),
    CONSTRAINT money_out_bearer_known   CHECK (fee_bearer IN ('CUSTOMER', 'ORIAH')),
    CONSTRAINT money_out_oriah_bears_all CHECK (fee_bearer = 'CUSTOMER' OR customer_fee = 0),
    CONSTRAINT money_out_currency       CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT money_out_method_known   CHECK (method IN ('CASH', 'MTN', 'AIRTEL', 'ZAMTEL', 'BANK_TRANSFER')),
    -- (method is NOT NULL; "reference IS NOT NULL" is never NULL.)
    CONSTRAINT money_out_reference_for_non_cash CHECK (method = 'CASH' OR reference IS NOT NULL),
    CONSTRAINT money_out_cash_costs_nothing CHECK (method <> 'CASH' OR (send_charge = 0 AND reference IS NULL AND reference_key IS NULL)),
    CONSTRAINT money_out_key_is_reference CHECK (reference_key IS NULL OR reference_key = method || ':' || reference),
    -- One spelling of a reference: upper case, no spaces, as an incoming reference is kept.
    CONSTRAINT money_out_reference_spelled CHECK (reference IS NULL OR (reference = upper(reference) AND reference !~ '\s')),
    CONSTRAINT money_out_backdate_length CHECK (backdate_reason IS NULL OR char_length(btrim(backdate_reason)) BETWEEN 5 AND 200)
);
CREATE UNIQUE INDEX uq_money_out_live          ON money_out (live_for) WHERE live_for IS NOT NULL;
CREATE UNIQUE INDEX uq_money_out_reference_key ON money_out (reference_key) WHERE reference_key IS NOT NULL;
CREATE INDEX money_out_refund_due ON money_out (refund_due_id) WHERE refund_due_id IS NOT NULL;
CREATE INDEX money_out_held_money ON money_out (held_money_id) WHERE held_money_id IS NOT NULL;
CREATE INDEX money_out_recorded ON money_out (recorded_at DESC);

-- A payout cancelled as recorded wrongly: once, with a reason.
CREATE TABLE money_out_void (
    id             UUID         PRIMARY KEY,
    money_out_id   UUID         NOT NULL UNIQUE REFERENCES money_out (id),
    voided_by      VARCHAR(128) NOT NULL,
    voided_by_name VARCHAR(100),
    reason         VARCHAR(200) NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 200),
    voided_at      TIMESTAMPTZ  NOT NULL
);
CREATE TRIGGER money_out_void_append_only_row BEFORE UPDATE OR DELETE ON money_out_void FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER money_out_void_no_truncate BEFORE TRUNCATE ON money_out_void FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- A payout pays exactly the debt it names, as the debt says, and only a debt that is owed.
CREATE FUNCTION money_out_matches_debt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    d refund_due%ROWTYPE;
    h held_money%ROWTYPE;
BEGIN
    IF NEW.refund_due_id IS NOT NULL THEN
        SELECT * INTO d FROM refund_due WHERE id = NEW.refund_due_id;
        IF d.status <> 'OWED' OR d.owed_gross <> NEW.gross OR d.currency <> NEW.currency OR d.fee_bearer <> NEW.fee_bearer
           OR d.confirmation_id <> NEW.confirmation_id OR d.order_id <> NEW.order_id OR d.kind <> NEW.cause THEN
            RAISE EXCEPTION 'money_out: it pays exactly an owed refund, as owed';
        END IF;
    ELSE
        SELECT * INTO h FROM held_money WHERE id = NEW.held_money_id;
        IF h.status <> 'OWED' OR h.reason = 'WRONG_DATE' OR h.owed_gross <> NEW.gross OR h.currency <> NEW.currency
           OR h.confirmation_id <> NEW.confirmation_id OR h.order_id <> NEW.order_id OR h.reason <> NEW.cause THEN
            RAISE EXCEPTION 'money_out: it pays exactly owed held money, as owed';
        END IF;
    END IF;
    IF NEW.live_for IS DISTINCT FROM COALESCE(NEW.refund_due_id, NEW.held_money_id) THEN
        RAISE EXCEPTION 'money_out: a new payout stands for its debt';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER money_out_matches BEFORE INSERT ON money_out FOR EACH ROW EXECUTE FUNCTION money_out_matches_debt();

-- A payout is never changed. The one thing that happens to it is that it is cancelled: it lets go of its debt and of its reference,
-- together, and only once a cancellation names it.
CREATE FUNCTION money_out_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- (net is generated from gross and customer_fee, and is not yet worked out when a BEFORE trigger runs: it is left out of the comparison,
    -- and cannot differ while those two are the same.)
    IF (to_jsonb(NEW) - 'live_for' - 'reference_key' - 'net') IS DISTINCT FROM (to_jsonb(OLD) - 'live_for' - 'reference_key' - 'net') THEN
        RAISE EXCEPTION 'money_out: a payout is never changed';
    END IF;
    IF NEW.live_for IS NOT NULL OR NEW.reference_key IS NOT NULL THEN
        RAISE EXCEPTION 'money_out: a cancelled payout lets go of its debt and its reference together';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM money_out_void v WHERE v.money_out_id = OLD.id) THEN
        RAISE EXCEPTION 'money_out: only a cancelled payout lets go of its debt';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER money_out_guard_update BEFORE UPDATE ON money_out FOR EACH ROW EXECUTE FUNCTION money_out_guard();
CREATE TRIGGER money_out_no_delete BEFORE DELETE ON money_out FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER money_out_no_truncate BEFORE TRUNCATE ON money_out FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- What is owed never changes. A debt is paid, owed again when its payout was cancelled, and, when it was in question, either made
-- payable or closed by the owner's word.
CREATE FUNCTION refund_due_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'status' - 'paid_money_out_id') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'paid_money_out_id') THEN
        RAISE EXCEPTION 'refund_due: what is owed cannot be changed';
    END IF;
    IF OLD.status = 'OWED' AND NEW.status = 'PAID'
       AND EXISTS (SELECT 1 FROM money_out WHERE id = NEW.paid_money_out_id AND live_for = OLD.id) THEN
        RETURN NEW;
    END IF;
    -- Only after its payout was cancelled.
    IF OLD.status = 'PAID' AND NEW.status = 'OWED' AND NEW.paid_money_out_id IS NULL
       AND NOT EXISTS (SELECT 1 FROM money_out WHERE live_for = OLD.id) THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'IN_QUESTION' AND NEW.status = 'OWED'
       AND EXISTS (SELECT 1 FROM refund_due_check WHERE refund_due_id = OLD.id AND money_arrived) THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'IN_QUESTION' AND NEW.status = 'CLOSED'
       AND EXISTS (SELECT 1 FROM refund_due_check WHERE refund_due_id = OLD.id AND NOT money_arrived) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'refund_due: % to % is not a change a debt can make', OLD.status, NEW.status;
END $$;
CREATE TRIGGER refund_due_guard_update BEFORE UPDATE ON refund_due FOR EACH ROW EXECUTE FUNCTION refund_due_guard();
CREATE TRIGGER refund_due_no_delete BEFORE DELETE ON refund_due FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER refund_due_no_truncate BEFORE TRUNCATE ON refund_due FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- Held money can now be paid back.
-- ------------------------------------------------------------------------------------------------------------------------------------------
ALTER TABLE held_money ADD COLUMN repaid_at TIMESTAMPTZ;
ALTER TABLE held_money DROP CONSTRAINT held_money_status_known;
ALTER TABLE held_money ADD CONSTRAINT held_money_status_known CHECK (status IN ('OWED', 'VOIDED', 'REPAID'));
ALTER TABLE held_money ADD CONSTRAINT held_money_repaid_timed CHECK ((status = 'REPAID') = (repaid_at IS NOT NULL));

-- What is owed never changes. It is voided (final), paid back, or owed again after its payout was cancelled; nothing else.
CREATE OR REPLACE FUNCTION held_money_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.confirmation_id, NEW.order_id, NEW.reason, NEW.owed_gross, NEW.currency, NEW.created_at)
       IS DISTINCT FROM (OLD.id, OLD.confirmation_id, OLD.order_id, OLD.reason, OLD.owed_gross, OLD.currency, OLD.created_at) THEN
        RAISE EXCEPTION 'held_money: what is owed cannot be changed';
    END IF;
    IF OLD.status = 'VOIDED' THEN
        RAISE EXCEPTION 'held_money: a voided record is final';
    END IF;
    IF OLD.status = 'OWED' AND NEW.status = 'VOIDED' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'OWED' AND NEW.status = 'REPAID' AND EXISTS (SELECT 1 FROM money_out WHERE live_for = OLD.id) THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'REPAID' AND NEW.status = 'OWED' AND NEW.repaid_at IS NULL
       AND NOT EXISTS (SELECT 1 FROM money_out WHERE live_for = OLD.id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'held_money: voided, paid back, or owed again after its payout was cancelled; nothing else';
END $$;
