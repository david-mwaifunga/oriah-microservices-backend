-- Something staff must look at. One live row per thing; a resolved one is history, and the same thing may
-- alert again later. An alert is a stored fact a screen can list; its emails are ordinary message rows
-- (recipient kind STAFF, one per mailbox) that the dispatcher sends like any other.
--
-- Never a cost figure, never anything personal, never free text a customer typed, never an address.
CREATE TABLE staff_alert (
    id              UUID          PRIMARY KEY,
    kind            VARCHAR(30)   NOT NULL,
    -- What it is about: an order id, a confirmation id, a variant id, a day, or a fixed word.
    subject_id      VARCHAR(64)   NOT NULL,
    order_id        UUID,
    audience        VARCHAR(10)   NOT NULL,
    state           VARCHAR(10)   NOT NULL,
    -- One line in plain words for a list, and the full words (the email's subject and body).
    summary         TEXT          NOT NULL,
    detail          TEXT          NOT NULL,
    raised_at       TIMESTAMPTZ   NOT NULL,
    -- When a mail server first ACCEPTED an email about it. NULL = nobody has been told by email yet.
    -- "Told" means accepted, never "written down": an alert whose email could not go is told again.
    emailed_at      TIMESTAMPTZ,
    -- TRUE when it was raised beyond the day's limit of payment-claim emails: it is listed, never emailed.
    email_limited   BOOLEAN       NOT NULL DEFAULT FALSE,
    -- The latest moment its emails are still worth sending; NULL = the general window.
    tell_before     TIMESTAMPTZ,
    -- When it is next told again if still open, and the words for that; NULL when it is not.
    remind_at       TIMESTAMPTZ,
    remind_subject  TEXT,
    remind_body     TEXT,
    reminders_sent  INTEGER       NOT NULL DEFAULT 0,
    -- When it closes by itself if nothing closed it; NULL only for the kinds notification resolves itself.
    closes_at       TIMESTAMPTZ,
    -- Below cost only: good rounds in a row that did not list the piece.
    absent_rounds   INTEGER       NOT NULL DEFAULT 0,
    resolved_at     TIMESTAMPTZ,
    resolved_how    VARCHAR(20),
    CONSTRAINT staff_alert_kind_known CHECK (kind IN
        ('PAYMENT_TO_CONFIRM', 'SETTLEMENT_STUCK', 'VOID_AFTER_SALE', 'CLAIM_EMAIL_LIMIT', 'EMAIL_DOWN',
         'BELOW_COST', 'BELOW_COST_CHECK_DOWN')),
    CONSTRAINT staff_alert_audience_by_kind CHECK (
           (kind IN ('BELOW_COST', 'BELOW_COST_CHECK_DOWN') AND audience = 'COST')
        OR (kind NOT IN ('BELOW_COST', 'BELOW_COST_CHECK_DOWN') AND audience = 'MONEY')),
    CONSTRAINT staff_alert_state_known CHECK (state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT staff_alert_resolved_whole
        CHECK ((state = 'RESOLVED') = (resolved_at IS NOT NULL) AND (state = 'RESOLVED') = (resolved_how IS NOT NULL)),
    CONSTRAINT staff_alert_resolved_how_known CHECK (resolved_how IS NULL OR resolved_how IN
        ('PAID', 'EXPIRED', 'CANCELLED', 'MONEY_RECORDED', 'UNSETTLED', 'VOIDED', 'NO_LONGER_BELOW', 'RECOVERED',
         'BY_CLOCK')),
    CONSTRAINT staff_alert_reminder_has_words
        CHECK (remind_at IS NULL OR (remind_subject IS NOT NULL AND remind_body IS NOT NULL)),
    CONSTRAINT staff_alert_only_a_claim_is_limited CHECK (NOT email_limited OR kind = 'PAYMENT_TO_CONFIRM'),
    CONSTRAINT staff_alert_counts_not_negative CHECK (absent_rounds >= 0 AND reminders_sent >= 0)
);

-- One open alert per thing: the insert that lands is the one that tells.
CREATE UNIQUE INDEX uq_staff_alert_open ON staff_alert (kind, subject_id) WHERE state = 'OPEN';
CREATE INDEX idx_staff_alert_subject ON staff_alert (kind, subject_id, raised_at DESC);
CREATE INDEX idx_staff_alert_order   ON staff_alert (kind, order_id, raised_at DESC) WHERE order_id IS NOT NULL;
CREATE INDEX idx_staff_alert_read    ON staff_alert (audience, raised_at DESC);
CREATE INDEX idx_staff_alert_clock   ON staff_alert (remind_at, closes_at) WHERE state = 'OPEN';

ALTER TABLE notification_log ADD CONSTRAINT fk_notification_log_alert
    FOREIGN KEY (alert_id) REFERENCES staff_alert (id);
