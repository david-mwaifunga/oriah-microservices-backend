-- Phase 11: every message gets a status, and delivered_at gets its true name.
--
-- Until now "sending" was a log line written on the thread that reads Kafka, and a row only said that a
-- message had been written down. From here a row says what became of it: RECORDED (waiting to be sent),
-- SENT (a mail server took it), FAILED (tried and given up, or handed over with no answer), NOT_SENT
-- (deliberately not sent, with the reason), BY_HAND (words for a person to send).

-- delivered_at was stamped when the row was written, before any send: it always meant "recorded at".
ALTER TABLE notification_log RENAME COLUMN delivered_at TO recorded_at;
ALTER TABLE notification_log ALTER COLUMN recorded_at DROP DEFAULT;

-- A caller with no account and a message to staff have no shopper: the owner column is empty for them.
ALTER TABLE notification_log ALTER COLUMN keycloak_id DROP NOT NULL;

ALTER TABLE notification_log
    ADD COLUMN recipient_kind     VARCHAR(10),
    ADD COLUMN audience           VARCHAR(10),
    -- Only on a message to staff: the shop mailbox it is for. Never a customer's address.
    ADD COLUMN staff_to           VARCHAR(254),
    ADD COLUMN status             VARCHAR(10),
    ADD COLUMN status_reason      VARCHAR(30),
    ADD COLUMN status_at          TIMESTAMPTZ,
    ADD COLUMN attempts           INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at    TIMESTAMPTZ,
    -- Set exactly while the row waits to be sent. It is also the lease: a sender that takes the row
    -- pushes this into the future, so no other sender takes it meanwhile.
    ADD COLUMN next_attempt_at    TIMESTAMPTZ,
    -- One fixed word for where the last try went wrong. Never text a mail server sent.
    ADD COLUMN last_error         VARCHAR(40),
    -- TRUE once a send got as far as handing the body over and no answer came: it may have arrived.
    ADD COLUMN maybe_handed_over  BOOLEAN NOT NULL DEFAULT FALSE,
    -- TRUE when what was emailed was the short "please sign in" note, not the stored words.
    ADD COLUMN short_form         BOOLEAN NOT NULL DEFAULT FALSE,
    -- The latest moment the message is still worth sending.
    ADD COLUMN send_before        TIMESTAMPTZ,
    -- When the mail server answered that it took the message. "Delivered to the mailbox" is never known.
    ADD COLUMN sent_at            TIMESTAMPTZ,
    -- The event that caused the row, where one did: which event, its place in the order's history, and
    -- when its producer wrote it down.
    ADD COLUMN event_id           UUID,
    ADD COLUMN event_seq          BIGINT,
    ADD COLUMN event_recorded_at  TIMESTAMPTZ,
    ADD COLUMN alert_id           UUID,
    ADD COLUMN alert_notice       VARCHAR(10),
    -- Which telling of that notice this is (1 for the first): a reminder or a re-telling may repeat.
    ADD COLUMN alert_tell_no      INTEGER;

-- Every row that exists was written when "sending" was a log line. None was ever emailed and none ever
-- will be: NOT_SENT is a final status, and the sender picks up RECORDED rows only.
UPDATE notification_log
   SET recipient_kind = 'SHOPPER',
       status         = 'NOT_SENT',
       status_reason  = 'RECORDED_BEFORE_SENDING',
       status_at      = recorded_at;

-- No default is left behind: a new row must say what it is, or the insert fails.
ALTER TABLE notification_log
    ALTER COLUMN recipient_kind SET NOT NULL,
    ALTER COLUMN status         SET NOT NULL,
    ALTER COLUMN status_at      SET NOT NULL;

ALTER TABLE notification_log
    ADD CONSTRAINT notification_log_recipient_known
        CHECK (recipient_kind IN ('SHOPPER', 'CALLER', 'STAFF')),
    ADD CONSTRAINT notification_log_channel_known
        CHECK (channel IN ('email', 'by-hand')),
    -- A shopper's row always has its owner; nobody else's row has one. Only a staff row names a mailbox.
    ADD CONSTRAINT notification_log_recipient_shape
        CHECK ((recipient_kind = 'SHOPPER' AND keycloak_id IS NOT NULL AND audience IS NULL
                    AND staff_to IS NULL AND channel = 'email')
            OR (recipient_kind = 'CALLER'  AND keycloak_id IS NULL AND order_id IS NOT NULL AND audience IS NULL
                    AND staff_to IS NULL AND channel = 'by-hand')
            -- "audience IS NOT NULL" is spelled out: a CHECK passes when its condition is NULL, and "NULL IN (...)" is NULL.
            OR (recipient_kind = 'STAFF'   AND keycloak_id IS NULL AND audience IS NOT NULL AND audience IN ('MONEY', 'COST')
                    AND channel = 'email')),
    -- A staff row is about an alert, never about one event: its type says which alert.
    ADD CONSTRAINT notification_log_staff_event_type
        CHECK ((recipient_kind = 'STAFF') = (event_type LIKE 'alert.%')),
    ADD CONSTRAINT notification_log_status_known
        CHECK (status IN ('RECORDED', 'SENT', 'FAILED', 'NOT_SENT', 'BY_HAND')),
    ADD CONSTRAINT notification_log_reason_known
        CHECK (status_reason IS NULL OR status_reason IN (
            'RECORDED_BEFORE_SENDING', 'BELL_ONLY', 'SENDING_OFF', 'TOO_LATE', 'BACKDATED_STEP', 'SUPERSEDED',
            'NO_ADDRESS', 'UNVERIFIED_ADDRESS', 'SANDBOX', 'NO_RECIPIENT_CONFIGURED', 'ALERT_RESOLVED',
            'DAILY_LIMIT', 'GAVE_UP', 'ADDRESS_REFUSED', 'OUTCOME_UNKNOWN')),
    -- What each status means, so that an impossible row cannot be written.
    ADD CONSTRAINT notification_log_status_shape
        CHECK ((status = 'RECORDED' AND next_attempt_at IS NOT NULL AND send_before IS NOT NULL
                    AND sent_at IS NULL AND status_reason IS NULL)
            OR (status = 'SENT'     AND sent_at IS NOT NULL AND next_attempt_at IS NULL AND status_reason IS NULL)
            OR (status = 'FAILED'   AND status_reason IS NOT NULL
                    AND status_reason IN ('GAVE_UP', 'ADDRESS_REFUSED', 'OUTCOME_UNKNOWN')
                    AND sent_at IS NULL AND next_attempt_at IS NULL)
            OR (status = 'NOT_SENT' AND status_reason IS NOT NULL
                    AND status_reason NOT IN ('GAVE_UP', 'ADDRESS_REFUSED', 'OUTCOME_UNKNOWN')
                    AND sent_at IS NULL AND next_attempt_at IS NULL)
            OR (status = 'BY_HAND'  AND recipient_kind = 'CALLER' AND status_reason IS NULL
                    AND sent_at IS NULL AND next_attempt_at IS NULL)),
    ADD CONSTRAINT notification_log_by_hand_only_for_a_caller
        CHECK (recipient_kind <> 'CALLER' OR status = 'BY_HAND'),
    ADD CONSTRAINT notification_log_notice_known
        CHECK (alert_notice IS NULL
            OR (recipient_kind = 'STAFF'
                AND alert_notice IN ('FIRST', 'REMINDER', 'AGAIN', 'SORTED', 'CLOSING', 'DIGEST'))),
    ADD CONSTRAINT notification_log_notice_whole
        CHECK ((alert_notice IS NULL) = (alert_tell_no IS NULL) AND (alert_tell_no IS NULL OR alert_tell_no >= 1)),
    -- A row of one alert always says which notice it is; a digest names several alerts and so names none here.
    ADD CONSTRAINT notification_log_alert_has_notice
        CHECK (alert_id IS NULL OR (alert_notice IS NOT NULL AND alert_notice <> 'DIGEST')),
    ADD CONSTRAINT notification_log_short_form_only_when_sent
        CHECK (NOT short_form OR (recipient_kind = 'SHOPPER' AND status = 'SENT')),
    ADD CONSTRAINT notification_log_maybe_only_after_a_try
        CHECK (NOT maybe_handed_over OR attempts > 0),
    ADD CONSTRAINT notification_log_attempts_not_negative
        CHECK (attempts >= 0);

-- One event makes at most one message row.
CREATE UNIQUE INDEX uq_notification_log_event ON notification_log (event_id) WHERE event_id IS NOT NULL;
-- One telling of one notice of one alert goes to one mailbox once (an empty mailbox counts as one too).
CREATE UNIQUE INDEX uq_notification_log_alert_notice
    ON notification_log (alert_id, alert_notice, alert_tell_no, staff_to) NULLS NOT DISTINCT
    WHERE alert_id IS NOT NULL;
-- What the sender looks for. No existing row is one.
CREATE INDEX idx_notification_log_due ON notification_log (next_attempt_at) WHERE status = 'RECORDED';
-- One order's shopper messages in the order they happened.
CREATE INDEX idx_notification_log_order_seq ON notification_log (order_id, event_seq) WHERE recipient_kind = 'SHOPPER';
-- Words waiting to be sent by hand, newest first (the sales work list).
CREATE INDEX idx_notification_log_by_hand ON notification_log (recorded_at DESC) WHERE status = 'BY_HAND';
-- /notifications/me: one person's rows, newest first. Replaces the single-column index.
DROP INDEX idx_notification_log_keycloak;
CREATE INDEX idx_notification_log_mine ON notification_log (keycloak_id, recorded_at DESC) WHERE keycloak_id IS NOT NULL;
