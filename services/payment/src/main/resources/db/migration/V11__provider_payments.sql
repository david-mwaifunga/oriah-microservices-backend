-- Phase 13: money collected through ZynlePay (mobile money and cards).
--
-- An ATTEMPT is one request to ZynlePay to collect an order's total from a wallet or a card. It is not money. Money is still a
-- payment_confirmation, the same record staff write: a verified attempt writes one, marked with its provider and the attempt it came from.
--
-- The rules these tables hold, whatever the code does:
--   * one attempt "holds" an order at a time (no second attempt, no other money recorded for it), from the moment it exists until it fails or is
--     verified, and never again after;
--   * what was asked for (who, which order, how much, under which reference, at which tariff) never changes;
--   * an attempt moves only along the allowed edges. Nothing ambiguous is a failure, and a FAILED attempt is never made VERIFIED directly: if
--     ZynlePay later says "paid", it becomes the owner's to decide (UNRESOLVED / PAID_AFTER_FAILED);
--   * a VERIFIED attempt has its money record, and one attempt has one standing money record.
--
-- oriah_refuse_change() is V5's function. Money is NUMERIC(12,2) with its currency beside it.

CREATE TABLE provider_payment (
    id                      UUID PRIMARY KEY,
    provider                VARCHAR(16)    NOT NULL,
    -- Ours: what ZynlePay is asked to collect under and what its callback names. Sent at most once, never used again.
    reference_no            VARCHAR(16)    NOT NULL,
    order_id                UUID           NOT NULL,
    keycloak_id             VARCHAR(64)    NOT NULL,       -- the shopper who started it
    channel                 VARCHAR(16)    NOT NULL,
    method                  VARCHAR(16)    NOT NULL,       -- the wallet (from the number's prefix) or CARD: what the money is recorded under
    -- The payer's number, in full: for the per-number limit, and for the owner to send a refund back to it. Never in an event or a log.
    -- Cleared (the only change it can take) once it is no longer needed.
    payer_msisdn            VARCHAR(13),
    -- Copied from the order's own answer, never sent by the shopper. Absent only before the order has answered.
    amount                  NUMERIC(12, 2),
    currency                VARCHAR(3),
    -- The tariff in force when the attempt started: a later change of tariff never re-prices it.
    fee_percent             NUMERIC(6, 3),
    fee_fixed               NUMERIC(12, 2),
    status                  VARCHAR(12)    NOT NULL,
    holds_order             BOOLEAN        NOT NULL DEFAULT true,
    failure_code            VARCHAR(24),
    unresolved_reason       VARCHAR(24),
    provider_code           VARCHAR(8),                    -- the last response code ZynlePay gave
    -- ZynlePay's and the network's own ids, as received, and beside each the one spelling staff references are compared in (NULL when it has none).
    -- Not unique: nothing yet proves ZynlePay never repeats one.
    provider_transaction_id  VARCHAR(128),
    provider_transaction_key VARCHAR(64),
    operator_reference       VARCHAR(128),
    operator_reference_key   VARCHAR(64),
    operator                VARCHAR(16),
    redirect_url            VARCHAR(1024),                 -- a card payment's page at ZynlePay
    idempotency_key         VARCHAR(128)   NOT NULL,
    request_fingerprint     VARCHAR(64)    NOT NULL,
    started_at              TIMESTAMPTZ    NOT NULL,
    sent_at                 TIMESTAMPTZ,                   -- when it became PENDING: committed BEFORE the request goes out
    next_check_at           TIMESTAMPTZ,                   -- when ZynlePay is next asked; NULL = not asked any more
    check_lease_until       TIMESTAMPTZ,                   -- one asker at a time: the checker, a staff check or the owner's resolve
    last_checked_at         TIMESTAMPTZ,
    check_count             INTEGER        NOT NULL DEFAULT 0,
    last_callback_at        TIMESTAMPTZ,
    callback_count          INTEGER        NOT NULL DEFAULT 0,
    final_recheck_at        TIMESTAMPTZ,                   -- a failed attempt is asked about once more, to catch ZynlePay contradicting itself
    provider_paid_at        TIMESTAMPTZ,
    verified_at             TIMESTAMPTZ,
    failed_at               TIMESTAMPTZ,
    unresolved_at           TIMESTAMPTZ,
    fee                     NUMERIC(12, 2),                -- a copy of what its money record carries
    fee_basis               VARCHAR(64),
    confirmation_id         UUID,                          -- its standing money record
    version                 BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_provider_payment_reference_no UNIQUE (reference_no),
    CONSTRAINT uq_provider_payment_key UNIQUE (keycloak_id, idempotency_key),
    CONSTRAINT provider_payment_provider_known CHECK (provider = 'ZYNLEPAY'),
    CONSTRAINT provider_payment_reference_is_ours CHECK (reference_no ~ '^OZ[0-9A-Z]{14}$'),
    CONSTRAINT provider_payment_channel_known CHECK (channel IN ('MOBILE_MONEY', 'CARD')),
    CONSTRAINT provider_payment_method_known CHECK (method IN ('MTN', 'AIRTEL', 'ZAMTEL', 'CARD')),
    CONSTRAINT provider_payment_card_is_card CHECK ((channel = 'CARD') = (method = 'CARD')),
    CONSTRAINT provider_payment_msisdn_shape CHECK (payer_msisdn IS NULL OR payer_msisdn ~ '^\+260[0-9]{9}$'),
    CONSTRAINT provider_payment_amount_positive CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT provider_payment_currency_known CHECK (currency IS NULL OR currency = 'ZMW'),
    CONSTRAINT provider_payment_amount_whole CHECK ((amount IS NULL) = (currency IS NULL) AND (amount IS NULL) = (fee_percent IS NULL)
                                                    AND (amount IS NULL) = (fee_fixed IS NULL)),
    -- Only an attempt that never got as far as being sent may be without its amount.
    CONSTRAINT provider_payment_sent_has_amount CHECK (amount IS NOT NULL OR status = 'STARTING'
                                                       OR (status = 'FAILED' AND failure_code IN ('ORDER_REFUSED', 'NOT_SENT'))),
    CONSTRAINT provider_payment_tariff_in_range CHECK (fee_percent IS NULL OR (fee_percent BETWEEN 0 AND 20 AND fee_fixed >= 0)),
    CONSTRAINT provider_payment_status_known CHECK (status IN ('STARTING', 'PENDING', 'UNRESOLVED', 'VERIFIED', 'FAILED')),
    CONSTRAINT provider_payment_failure_known
        CHECK (failure_code IS NULL OR failure_code IN ('ORDER_REFUSED', 'NOT_SENT', 'PROVIDER_REFUSED', 'DECLINED', 'MARKED_NOT_PAID')),
    CONSTRAINT provider_payment_reason_known
        CHECK (unresolved_reason IS NULL
               OR unresolved_reason IN ('NO_ANSWER', 'AMOUNT_DIFFERS', 'SAME_MONEY_ON_RECORD', 'CONFIRMATION_VOIDED', 'PAID_AFTER_FAILED')),
    CONSTRAINT provider_payment_failed_has_code CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT provider_payment_unresolved_has_reason CHECK ((status = 'UNRESOLVED') = (unresolved_reason IS NOT NULL)),
    CONSTRAINT provider_payment_final_holds_nothing CHECK (NOT (holds_order AND status IN ('FAILED', 'VERIFIED'))),
    CONSTRAINT provider_payment_live_holds_order CHECK (holds_order OR status NOT IN ('STARTING', 'PENDING')),
    CONSTRAINT provider_payment_verified_has_record CHECK (status <> 'VERIFIED' OR confirmation_id IS NOT NULL),
    CONSTRAINT provider_payment_fee_whole CHECK ((fee IS NULL) = (fee_basis IS NULL) AND (fee IS NULL OR fee >= 0)),
    CONSTRAINT provider_payment_counts_positive CHECK (check_count >= 0 AND callback_count >= 0)
);

-- One attempt holds an order at a time.
CREATE UNIQUE INDEX uq_provider_payment_one_holding_order ON provider_payment (order_id) WHERE holds_order;
CREATE INDEX provider_payment_due ON provider_payment (next_check_at) WHERE next_check_at IS NOT NULL;
CREATE INDEX provider_payment_final_recheck ON provider_payment (final_recheck_at) WHERE final_recheck_at IS NOT NULL;
CREATE INDEX provider_payment_order ON provider_payment (order_id);
CREATE INDEX provider_payment_by_shopper ON provider_payment (keycloak_id, started_at);
CREATE INDEX provider_payment_by_number ON provider_payment (payer_msisdn, started_at) WHERE payer_msisdn IS NOT NULL;
CREATE INDEX provider_payment_transaction_key ON provider_payment (provider_transaction_key) WHERE provider_transaction_key IS NOT NULL;
CREATE INDEX provider_payment_operator_key ON provider_payment (operator_reference_key) WHERE operator_reference_key IS NOT NULL;
CREATE INDEX provider_payment_open ON provider_payment (started_at) WHERE status IN ('STARTING', 'PENDING', 'UNRESOLVED');

CREATE FUNCTION provider_payment_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    allowed BOOLEAN;
BEGIN
    IF (NEW.id, NEW.provider, NEW.reference_no, NEW.order_id, NEW.keycloak_id, NEW.channel, NEW.method, NEW.idempotency_key, NEW.request_fingerprint,
        NEW.started_at)
       IS DISTINCT FROM
       (OLD.id, OLD.provider, OLD.reference_no, OLD.order_id, OLD.keycloak_id, OLD.channel, OLD.method, OLD.idempotency_key, OLD.request_fingerprint,
        OLD.started_at) THEN
        RAISE EXCEPTION 'provider_payment: who is paying, for which order, by what and under which reference cannot be changed';
    END IF;
    IF NEW.payer_msisdn IS NOT NULL AND NEW.payer_msisdn IS DISTINCT FROM OLD.payer_msisdn THEN
        RAISE EXCEPTION 'provider_payment: a payer''s number can only be cleared, never set or changed';
    END IF;
    IF (OLD.amount IS NOT NULL AND (NEW.amount, NEW.currency) IS DISTINCT FROM (OLD.amount, OLD.currency))
       OR (OLD.fee_percent IS NOT NULL AND (NEW.fee_percent, NEW.fee_fixed) IS DISTINCT FROM (OLD.fee_percent, OLD.fee_fixed)) THEN
        RAISE EXCEPTION 'provider_payment: the amount and the tariff are set once';
    END IF;
    IF NEW.holds_order AND NOT OLD.holds_order THEN
        RAISE EXCEPTION 'provider_payment: an attempt that has let go of its order never holds its order again';
    END IF;
    IF NEW.confirmation_id IS DISTINCT FROM OLD.confirmation_id AND OLD.confirmation_id IS NOT NULL THEN
        IF NEW.confirmation_id IS NOT NULL THEN
            RAISE EXCEPTION 'provider_payment: a money record is let go before another takes its place';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM payment_confirmation_void v WHERE v.confirmation_id = OLD.confirmation_id) THEN
            RAISE EXCEPTION 'provider_payment: its money record still stands; only a cancelled record is let go';
        END IF;
    END IF;

    -- The moves. A row that keeps its status and its code or reason is bookkeeping (when it was asked about, what was answered).
    IF (NEW.status, NEW.failure_code, NEW.unresolved_reason) IS NOT DISTINCT FROM (OLD.status, OLD.failure_code, OLD.unresolved_reason) THEN
        RETURN NEW;
    END IF;
    allowed := CASE OLD.status
        WHEN 'STARTING' THEN
            NEW.status = 'PENDING' OR (NEW.status = 'FAILED' AND NEW.failure_code IN ('ORDER_REFUSED', 'NOT_SENT'))
        WHEN 'PENDING' THEN
            NEW.status = 'VERIFIED'
            OR (NEW.status = 'FAILED' AND NEW.failure_code IN ('NOT_SENT', 'PROVIDER_REFUSED', 'DECLINED'))
            OR (NEW.status = 'UNRESOLVED' AND NEW.unresolved_reason IN ('NO_ANSWER', 'AMOUNT_DIFFERS', 'SAME_MONEY_ON_RECORD'))
        WHEN 'UNRESOLVED' THEN
            NEW.status = 'VERIFIED'
            OR (NEW.status = 'FAILED' AND NEW.failure_code IN ('DECLINED', 'MARKED_NOT_PAID'))
            -- an attempt already the owner's for want of an answer may still learn why the machine cannot settle it
            OR (NEW.status = 'UNRESOLVED' AND NEW.unresolved_reason IN ('SAME_MONEY_ON_RECORD', 'AMOUNT_DIFFERS')
                AND OLD.unresolved_reason IN ('NO_ANSWER', 'AMOUNT_DIFFERS'))
        WHEN 'FAILED' THEN
            -- Never to VERIFIED: ZynlePay saying "paid" of a failed attempt is the owner's to decide. And never from a failure that proves
            -- nothing reached ZynlePay.
            NEW.status = 'UNRESOLVED' AND NEW.unresolved_reason = 'PAID_AFTER_FAILED'
            AND OLD.failure_code IN ('PROVIDER_REFUSED', 'DECLINED', 'MARKED_NOT_PAID') AND OLD.sent_at IS NOT NULL
        WHEN 'VERIFIED' THEN
            NEW.status = 'UNRESOLVED' AND NEW.unresolved_reason = 'CONFIRMATION_VOIDED'
        ELSE false
    END;
    IF NOT COALESCE(allowed, false) THEN
        RAISE EXCEPTION 'provider_payment: a % attempt cannot become %', OLD.status || COALESCE('(' || COALESCE(OLD.failure_code, OLD.unresolved_reason) || ')', ''),
            NEW.status || COALESCE('(' || COALESCE(NEW.failure_code, NEW.unresolved_reason) || ')', '');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_payment_guard_update
    BEFORE UPDATE ON provider_payment
    FOR EACH ROW EXECUTE FUNCTION provider_payment_guard();

CREATE TRIGGER provider_payment_no_delete
    BEFORE DELETE ON provider_payment
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER provider_payment_no_truncate
    BEFORE TRUNCATE ON provider_payment
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- Every time ZynlePay was asked something about an attempt, and what it said, in Oriah's own words. Never a body, a credential or a number.
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE provider_payment_check (
    id                BIGSERIAL PRIMARY KEY,
    payment_id        UUID          NOT NULL REFERENCES provider_payment (id),
    source            VARCHAR(8)    NOT NULL,
    asked_by          VARCHAR(128)  NOT NULL,             -- system:zynlepay, or user:<subject> for a staff check or the owner's resolve
    asked_at          TIMESTAMPTZ   NOT NULL,
    answered_at       TIMESTAMPTZ   NOT NULL,
    outcome           VARCHAR(12)   NOT NULL,
    provider_code     VARCHAR(8),
    reported_amount   NUMERIC(14, 2),                     -- what ZynlePay said was paid, when it said
    reported_currency VARCHAR(8),
    reported_paid_at  TIMESTAMPTZ,
    CONSTRAINT provider_payment_check_source_known CHECK (source IN ('START', 'POLL', 'STAFF', 'RESOLVE')),
    CONSTRAINT provider_payment_check_outcome_known
        CHECK (outcome IN ('ACCEPTED', 'REDIRECT', 'REFUSED', 'CREDENTIALS', 'NOT_SENT', 'UNCLEAR', 'PAID', 'PENDING', 'FAILED', 'NOT_FOUND', 'UNREADABLE',
                           'NO_ANSWER'))
);

CREATE INDEX provider_payment_check_payment ON provider_payment_check (payment_id, id);

CREATE TRIGGER provider_payment_check_append_only_row
    BEFORE UPDATE OR DELETE ON provider_payment_check
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER provider_payment_check_append_only_truncate
    BEFORE TRUNCATE ON provider_payment_check
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- The owner's decisions about an attempt ZynlePay did not settle: paid or not paid, from ZynlePay's own records. Added, never edited. The
-- evidence is kept here and nowhere else (no event carries it).
-- ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE provider_payment_resolution (
    id                  UUID PRIMARY KEY,
    payment_id          UUID           NOT NULL REFERENCES provider_payment (id),
    idempotency_key     VARCHAR(128)   NOT NULL UNIQUE,
    request_fingerprint VARCHAR(64)    NOT NULL,
    outcome             VARCHAR(8)     NOT NULL,
    evidence            VARCHAR(200)   NOT NULL,
    gross               NUMERIC(12, 2),                   -- only where ZynlePay reported another amount than was asked
    arrived_at          TIMESTAMPTZ,
    override_pending    BOOLEAN        NOT NULL,          -- "not paid" said while ZynlePay was still answering "deciding"
    check_outcome       VARCHAR(12),                      -- what ZynlePay answered when it was asked just before
    decided_by          VARCHAR(128)   NOT NULL,
    decided_by_name     VARCHAR(100),
    decided_at          TIMESTAMPTZ    NOT NULL,
    resulting_status    VARCHAR(12)    NOT NULL,
    confirmation_id     UUID,
    CONSTRAINT provider_payment_resolution_outcome_known CHECK (outcome IN ('PAID', 'NOT_PAID')),
    CONSTRAINT provider_payment_resolution_evidence_length CHECK (char_length(btrim(evidence)) BETWEEN 10 AND 200),
    CONSTRAINT provider_payment_resolution_gross_positive CHECK (gross IS NULL OR gross > 0)
);

CREATE INDEX provider_payment_resolution_payment ON provider_payment_resolution (payment_id, decided_at);

CREATE TRIGGER provider_payment_resolution_append_only_row
    BEFORE UPDATE OR DELETE ON provider_payment_resolution
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

CREATE TRIGGER provider_payment_resolution_append_only_truncate
    BEFORE TRUNCATE ON provider_payment_resolution
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- ------------------------------------------------------------------------------------------------------------------------------------------
-- The money record: the same one staff write, now also written from a verified attempt.
--
-- provider and provider_payment_id say where the money came from, for good. provider_payment_key is the same id while the record STANDS and is
-- released by a void (exactly as reference_key is), so one attempt has one standing record and a cancelled one can be replaced by the owner.
-- CARD is a method only a provider can record: staff never key a card payment.
-- ------------------------------------------------------------------------------------------------------------------------------------------
ALTER TABLE payment_confirmation
    ADD COLUMN provider             VARCHAR(16),
    ADD COLUMN provider_payment_id  UUID REFERENCES provider_payment (id),
    ADD COLUMN provider_payment_key UUID,
    ADD CONSTRAINT uq_payment_confirmation_provider_payment_key UNIQUE (provider_payment_key),
    ADD CONSTRAINT payment_confirmation_provider_known CHECK (provider IS NULL OR provider = 'ZYNLEPAY'),
    ADD CONSTRAINT payment_confirmation_provider_has_attempt CHECK ((provider IS NULL) = (provider_payment_id IS NULL)),
    ADD CONSTRAINT payment_confirmation_provider_key_is_the_attempt CHECK (provider_payment_key IS NULL OR provider_payment_key = provider_payment_id),
    DROP CONSTRAINT payment_confirmation_method_known,
    ADD CONSTRAINT payment_confirmation_method_known CHECK (method IN ('CASH', 'MTN', 'AIRTEL', 'ZAMTEL', 'BANK_TRANSFER', 'CARD')),
    ADD CONSTRAINT payment_confirmation_card_is_a_providers CHECK (method <> 'CARD' OR provider IS NOT NULL);

CREATE INDEX payment_confirmation_provider_payment ON payment_confirmation (provider_payment_id) WHERE provider_payment_id IS NOT NULL;

-- V6's guard, with the two new facts frozen and the new key releasable only.
CREATE OR REPLACE FUNCTION payment_confirmation_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.order_id, NEW.method, NEW.reference, NEW.gross, NEW.fee, NEW.currency, NEW.arrived_at, NEW.backdate_reason,
        NEW.confirmed_by, NEW.confirmed_by_name, NEW.confirmed_at, NEW.idempotency_key, NEW.request_fingerprint, NEW.provider, NEW.provider_payment_id)
       IS DISTINCT FROM
       (OLD.id, OLD.order_id, OLD.method, OLD.reference, OLD.gross, OLD.fee, OLD.currency, OLD.arrived_at, OLD.backdate_reason,
        OLD.confirmed_by, OLD.confirmed_by_name, OLD.confirmed_at, OLD.idempotency_key, OLD.request_fingerprint, OLD.provider, OLD.provider_payment_id) THEN
        RAISE EXCEPTION 'payment_confirmation: the money, who confirmed it and where it came from cannot be changed';
    END IF;
    IF NEW.reference_key IS NOT NULL AND NEW.reference_key IS DISTINCT FROM OLD.reference_key THEN
        RAISE EXCEPTION 'payment_confirmation: a reference key can only be released, never set or changed';
    END IF;
    IF NEW.provider_payment_key IS NOT NULL AND NEW.provider_payment_key IS DISTINCT FROM OLD.provider_payment_key THEN
        RAISE EXCEPTION 'payment_confirmation: a provider payment key can only be released, never set or changed';
    END IF;
    IF OLD.outcome <> 'PENDING'
       AND (NEW.outcome IS DISTINCT FROM OLD.outcome OR NEW.outcome_reason IS DISTINCT FROM OLD.outcome_reason
            OR NEW.outcome_at IS DISTINCT FROM OLD.outcome_at) THEN
        RAISE EXCEPTION 'payment_confirmation: the outcome of a confirmation is final once set';
    END IF;
    RETURN NEW;
END;
$$;
