-- Phase 7 (slice 7.4): money reaches an order by a person confirming it.
--
-- Staff confirm what was received (method, reference, gross, fee, the date the money arrived, who), reject a false claim, or void a confirmation
-- keyed wrongly. Everything here is a RECORD: amounts and identities are never changed, only the outcome of a confirmation is set once (what
-- became of the money: it paid a sale, or it is held for the customer), and a void releases the reference so the corrected confirmation can use it.
--
-- No foreign key to payment_intents (the card path's table, which eleven test classes clear): these tables stand on their own. oriah_refuse_change()
-- is V5's function. Money is NUMERIC(12,2) in whole ngwee, with its currency beside it.

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- What a shopper SAID they paid with (from order.payment_submitted). A claim is not money: it is what a rejection is bound to.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE payment_claim (
    id              UUID PRIMARY KEY,                -- the claim id, minted by order: the same event twice is the same claim
    order_id        UUID         NOT NULL,
    method          VARCHAR(16)  NOT NULL,
    reference       VARCHAR(64)  NOT NULL,           -- in its one spelling (upper case, no spaces)
    submitted_at    TIMESTAMPTZ  NOT NULL,
    hold_expires_at TIMESTAMPTZ,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payment_claim_method_known CHECK (method IN ('MTN', 'AIRTEL', 'ZAMTEL', 'BANK_TRANSFER')),
    CONSTRAINT payment_claim_reference_spelled CHECK (reference = upper(reference) AND reference !~ '\s')
);

CREATE INDEX payment_claim_order ON payment_claim (order_id);
CREATE INDEX payment_claim_reference ON payment_claim (method, reference);

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- A confirmation: money that reached Oriah, as a person recorded it.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE payment_confirmation (
    id                  UUID PRIMARY KEY,
    order_id            UUID           NOT NULL,       -- NOT checked against order: payment never reads an order (money for a wrong or unknown order is held)
    method              VARCHAR(16)    NOT NULL,
    reference           VARCHAR(64),                   -- in its one spelling; cash may have none
    -- 'MTN:ABC123' for a non-cash payment: the one key that makes a reference confirm money once. NULL for cash, and NULL once a void has
    -- released it (the only change the key can take), so a corrected confirmation may use the same reference.
    reference_key       VARCHAR(81),
    gross               NUMERIC(12, 2) NOT NULL,
    fee                 NUMERIC(12, 2) NOT NULL DEFAULT 0,
    net                 NUMERIC(12, 2) GENERATED ALWAYS AS (gross - fee) STORED,    -- worked out, never written, never accepted from a request
    currency            VARCHAR(3)     NOT NULL,
    arrived_at          TIMESTAMPTZ    NOT NULL,       -- the day the money arrived: what the books are dated by
    backdate_reason     VARCHAR(200),
    confirmed_by        VARCHAR(128)   NOT NULL,
    confirmed_by_name   VARCHAR(100),
    confirmed_at        TIMESTAMPTZ    NOT NULL,       -- when it was keyed
    idempotency_key     VARCHAR(128)   NOT NULL UNIQUE,
    request_fingerprint VARCHAR(64)    NOT NULL,       -- so the same key with a different body is told apart from a repeat
    -- What became of the money. PENDING until order says: SETTLED (it paid the sale; order.paid) or HELD (it could not, and is owed back).
    outcome             VARCHAR(8)     NOT NULL DEFAULT 'PENDING',
    outcome_reason      VARCHAR(24),
    outcome_at          TIMESTAMPTZ,
    -- Bookkeeping of the watcher that announces a PENDING confirmation again: how many times it has been announced, and when next.
    emit_attempts       INTEGER        NOT NULL DEFAULT 1,
    next_emit_at        TIMESTAMPTZ,
    CONSTRAINT uq_payment_confirmation_reference_key UNIQUE (reference_key),
    CONSTRAINT payment_confirmation_method_known CHECK (method IN ('CASH', 'MTN', 'AIRTEL', 'ZAMTEL', 'BANK_TRANSFER')),
    CONSTRAINT payment_confirmation_gross_positive CHECK (gross > 0),
    CONSTRAINT payment_confirmation_fee_in_range CHECK (fee >= 0 AND fee <= gross),
    CONSTRAINT payment_confirmation_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payment_confirmation_reference_for_non_cash CHECK (method = 'CASH' OR reference IS NOT NULL),
    CONSTRAINT payment_confirmation_cash_has_no_key CHECK (method <> 'CASH' OR reference_key IS NULL),
    CONSTRAINT payment_confirmation_key_is_the_reference CHECK (reference_key IS NULL OR reference_key = method || ':' || reference),
    CONSTRAINT payment_confirmation_backdate_reason_length CHECK (backdate_reason IS NULL OR char_length(btrim(backdate_reason)) BETWEEN 5 AND 200),
    CONSTRAINT payment_confirmation_outcome_known CHECK (outcome IN ('PENDING', 'SETTLED', 'HELD')),
    CONSTRAINT payment_confirmation_outcome_timed CHECK ((outcome = 'PENDING') = (outcome_at IS NULL)),
    CONSTRAINT payment_confirmation_held_has_reason CHECK (outcome <> 'HELD' OR outcome_reason IS NOT NULL),
    CONSTRAINT payment_confirmation_emit_attempts_positive CHECK (emit_attempts >= 1)
);

CREATE INDEX payment_confirmation_order ON payment_confirmation (order_id);
CREATE INDEX payment_confirmation_pending ON payment_confirmation (confirmed_at) WHERE outcome = 'PENDING';

-- A confirmation is a record of money. What is changed after the fact is only: its outcome, set once from PENDING; the watcher's bookkeeping;
-- and the reference key, which can only be released.
CREATE FUNCTION payment_confirmation_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.order_id, NEW.method, NEW.reference, NEW.gross, NEW.fee, NEW.currency, NEW.arrived_at, NEW.backdate_reason,
        NEW.confirmed_by, NEW.confirmed_by_name, NEW.confirmed_at, NEW.idempotency_key, NEW.request_fingerprint)
       IS DISTINCT FROM
       (OLD.id, OLD.order_id, OLD.method, OLD.reference, OLD.gross, OLD.fee, OLD.currency, OLD.arrived_at, OLD.backdate_reason,
        OLD.confirmed_by, OLD.confirmed_by_name, OLD.confirmed_at, OLD.idempotency_key, OLD.request_fingerprint) THEN
        RAISE EXCEPTION 'payment_confirmation: the money and who confirmed it cannot be changed';
    END IF;
    IF NEW.reference_key IS NOT NULL AND NEW.reference_key IS DISTINCT FROM OLD.reference_key THEN
        RAISE EXCEPTION 'payment_confirmation: a reference key can only be released, never set or changed';
    END IF;
    IF OLD.outcome <> 'PENDING'
       AND (NEW.outcome IS DISTINCT FROM OLD.outcome OR NEW.outcome_reason IS DISTINCT FROM OLD.outcome_reason
            OR NEW.outcome_at IS DISTINCT FROM OLD.outcome_at) THEN
        RAISE EXCEPTION 'payment_confirmation: the outcome of a confirmation is final once set';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_confirmation_guard_update
    BEFORE UPDATE ON payment_confirmation
    FOR EACH ROW EXECUTE FUNCTION payment_confirmation_guard();

CREATE TRIGGER payment_confirmation_no_delete
    BEFORE DELETE ON payment_confirmation
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER payment_confirmation_no_truncate
    BEFORE TRUNCATE ON payment_confirmation
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- A void: a confirmation keyed wrongly, taken back. Only one whose money has not paid a sale; the replacement is a new confirmation.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE payment_confirmation_void (
    id              UUID PRIMARY KEY,
    confirmation_id UUID         NOT NULL UNIQUE REFERENCES payment_confirmation (id),
    voided_by       VARCHAR(128) NOT NULL,
    voided_by_name  VARCHAR(100),
    reason          VARCHAR(200) NOT NULL,
    voided_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT payment_confirmation_void_reason_length CHECK (char_length(btrim(reason)) BETWEEN 10 AND 200)
);

CREATE TRIGGER payment_confirmation_void_append_only_row
    BEFORE UPDATE OR DELETE ON payment_confirmation_void
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER payment_confirmation_void_append_only_truncate
    BEFORE TRUNCATE ON payment_confirmation_void
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- Money that came in and could not pay a sale: owed back to the customer, never revenue. One record per confirmation.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE held_money (
    id              UUID PRIMARY KEY,
    confirmation_id UUID           NOT NULL UNIQUE REFERENCES payment_confirmation (id),
    order_id        UUID           NOT NULL,
    reason          VARCHAR(24)    NOT NULL,
    owed_gross      NUMERIC(12, 2) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    status          VARCHAR(8)     NOT NULL DEFAULT 'OWED',
    created_at      TIMESTAMPTZ    NOT NULL,
    voided_at       TIMESTAMPTZ,
    CONSTRAINT held_money_reason_known CHECK (reason IN ('WRONG_AMOUNT', 'ORDER_CANCELLED', 'PIECE_SOLD', 'ALREADY_PAID', 'ORDER_UNKNOWN')),
    CONSTRAINT held_money_owed_positive CHECK (owed_gross > 0),
    CONSTRAINT held_money_status_known CHECK (status IN ('OWED', 'VOIDED')),
    CONSTRAINT held_money_status_voided_timed CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL))
);

CREATE INDEX held_money_owed ON held_money (created_at) WHERE status = 'OWED';

-- What is owed never changes; the only thing that happens to a record is that its confirmation is voided, which closes it for good.
CREATE FUNCTION held_money_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.confirmation_id, NEW.order_id, NEW.reason, NEW.owed_gross, NEW.currency, NEW.created_at)
       IS DISTINCT FROM (OLD.id, OLD.confirmation_id, OLD.order_id, OLD.reason, OLD.owed_gross, OLD.currency, OLD.created_at) THEN
        RAISE EXCEPTION 'held_money: what is owed cannot be changed';
    END IF;
    IF OLD.status = 'VOIDED' THEN
        RAISE EXCEPTION 'held_money: a voided record is final';
    END IF;
    IF NEW.status <> 'VOIDED' THEN
        RAISE EXCEPTION 'held_money: the only change is to void it';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER held_money_guard_update
    BEFORE UPDATE ON held_money
    FOR EACH ROW EXECUTE FUNCTION held_money_guard();

CREATE TRIGGER held_money_no_delete
    BEFORE DELETE ON held_money
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER held_money_no_truncate
    BEFORE TRUNCATE ON held_money
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- A rejection: staff say a claim is false. Bound to the claim, so what is rejected (order, method, reference) is copied from it, never typed.
-- Posts nothing and uses no reference key.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE payment_rejection (
    id              UUID PRIMARY KEY,
    claim_id        UUID         NOT NULL UNIQUE REFERENCES payment_claim (id),
    order_id        UUID         NOT NULL,
    method          VARCHAR(16)  NOT NULL,
    reference       VARCHAR(64)  NOT NULL,
    proof_type      VARCHAR(24)  NOT NULL,
    evidence        VARCHAR(500),
    rejected_by     VARCHAR(128) NOT NULL,
    rejected_by_name VARCHAR(100),
    rejected_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT payment_rejection_proof_known CHECK (proof_type IN ('NOT_FOUND_AT_PROVIDER', 'REFERENCE_ALREADY_USED')),
    CONSTRAINT payment_rejection_evidence_for_not_found
        CHECK (proof_type <> 'NOT_FOUND_AT_PROVIDER' OR (evidence IS NOT NULL AND char_length(btrim(evidence)) >= 10))
);

CREATE TRIGGER payment_rejection_append_only_row
    BEFORE UPDATE OR DELETE ON payment_rejection
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER payment_rejection_append_only_truncate
    BEFORE TRUNCATE ON payment_rejection
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER payment_claim_append_only_row
    BEFORE UPDATE OR DELETE ON payment_claim
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER payment_claim_append_only_truncate
    BEFORE TRUNCATE ON payment_claim
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
