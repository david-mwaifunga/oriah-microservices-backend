-- Phase 12: returns, refunds and cancellations of paid orders.
--
-- Customers are told that a paid order was cancelled, that a return was received and that a refund was paid. Staff who look after money
-- are told when a refund has been owed for days and is not recorded as paid.

-- 1. A new alert, and three new ways an alert ends.
ALTER TABLE staff_alert DROP CONSTRAINT staff_alert_kind_known;
ALTER TABLE staff_alert ADD CONSTRAINT staff_alert_kind_known CHECK (kind IN
    ('PAYMENT_TO_CONFIRM', 'SETTLEMENT_STUCK', 'VOID_AFTER_SALE', 'CLAIM_EMAIL_LIMIT', 'EMAIL_DOWN',
     'BELOW_COST', 'BELOW_COST_CHECK_DOWN', 'REFUND_TO_PAY'));
ALTER TABLE staff_alert DROP CONSTRAINT staff_alert_resolved_how_known;
ALTER TABLE staff_alert ADD CONSTRAINT staff_alert_resolved_how_known CHECK (resolved_how IS NULL OR resolved_how IN
    ('PAID', 'EXPIRED', 'CANCELLED', 'MONEY_RECORDED', 'UNSETTLED', 'VOIDED', 'NO_LONGER_BELOW', 'RECOVERED',
     'BY_CLOCK', 'PAID_OUT', 'MONEY_NEVER_CAME', 'SALE_CORRECTED'));
-- staff_alert_audience_by_kind (V8) is unchanged: REFUND_TO_PAY is not a cost alert, so it is told to the money mailboxes.

-- 2. What notification remembers of an order gains "a paid order was cancelled", set once from order.sale_cancelled.
ALTER TABLE order_ref ADD COLUMN cancelled_at TIMESTAMPTZ;

-- 3. A message may wait behind another message, named by its id. "Your refund has been paid" comes from payment's topic and the
--    cancellation (or the return) it follows from order's topic; event sequence numbers are per topic and aggregate, so they cannot
--    order the two. (No foreign key: both rows are written in one transaction and are never deleted apart.)
ALTER TABLE notification_log ADD COLUMN after_message_id UUID;

-- 4. One row per debt (the debt's id is the correction's). The alert is decided from this row after EITHER topic's fact lands, so the
--    order the facts arrive in never leaves an alert open or shut wrongly.
CREATE TABLE refund_watch (
    debt_id     UUID           PRIMARY KEY,
    order_id    UUID,
    owed_gross  NUMERIC(12, 2),
    currency    VARCHAR(3),
    -- When the correction happened: set by order's fact. NULL = notification has not heard of the correction yet.
    owed_since  TIMESTAMPTZ,
    -- The message written for the customer about the correction, if one was: "refund paid" waits behind it.
    message_id  UUID,
    -- Set by payment.refund_closed: the money this debt would have returned never arrived.
    closed_at   TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ    NOT NULL,
    -- What is owed comes whole or not at all.
    CONSTRAINT refund_watch_owed_whole CHECK ((owed_since IS NULL) = (owed_gross IS NULL) AND (owed_since IS NULL) = (currency IS NULL)
        AND (owed_since IS NULL) = (order_id IS NULL))
);

-- A payout of a debt, as payment announced it: paid, and perhaps cancelled since. The amounts are kept so that "your refund has been
-- paid" can be written when the correction is heard of second.
CREATE TABLE refund_watch_payout (
    payout_id         UUID           PRIMARY KEY,
    debt_id           UUID           NOT NULL,
    paid_seen         BOOLEAN        NOT NULL DEFAULT FALSE,
    voided_seen       BOOLEAN        NOT NULL DEFAULT FALSE,
    gross             NUMERIC(12, 2),
    customer_fee      NUMERIC(12, 2),
    net               NUMERIC(12, 2),
    currency          VARCHAR(3),
    event_id          UUID,
    event_recorded_at TIMESTAMPTZ,
    -- Whether the customer's message about it has been decided (written, or deliberately not).
    told              BOOLEAN        NOT NULL DEFAULT FALSE,
    -- A payout that was seen paid carries its figures. (paid_seen is NOT NULL, and "x IS NOT NULL" is never NULL.)
    CONSTRAINT refund_watch_payout_paid_whole CHECK (NOT paid_seen OR (gross IS NOT NULL AND customer_fee IS NOT NULL AND net IS NOT NULL
        AND currency IS NOT NULL AND event_id IS NOT NULL AND event_recorded_at IS NOT NULL))
);
CREATE INDEX refund_watch_payout_debt ON refund_watch_payout (debt_id);
