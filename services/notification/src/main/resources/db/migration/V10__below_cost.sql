-- The below-cost check's own state: one row. Technical state, not business data.
CREATE TABLE below_cost_poll (
    id               SMALLINT     PRIMARY KEY,
    -- Only one instance runs a round at a time: whoever sets this to a future moment has the round.
    leased_until     TIMESTAMPTZ,
    last_attempt_at  TIMESTAMPTZ,
    last_success_at  TIMESTAMPTZ,
    -- When the check first failed since its last good round; NULL = the last round was good. Kept here so
    -- that a restart in the middle of an outage keeps counting from the same moment.
    down_since       TIMESTAMPTZ,
    last_failure     VARCHAR(20),
    CONSTRAINT below_cost_poll_single_row CHECK (id = 1),
    CONSTRAINT below_cost_poll_failure_known CHECK (last_failure IS NULL OR last_failure IN
        ('UNAVAILABLE', 'REFUSED', 'TIMEOUT', 'TOKEN', 'INCONSISTENT', 'NO_SECRET', 'OTHER'))
);
INSERT INTO below_cost_poll (id) VALUES (1);

-- One email can name many pieces. A digest's rows (one per mailbox) share a digest id, and the table below
-- says which alerts that digest named, so that "told" can be stamped on each when a mail server takes it,
-- and a digest all of whose pieces have cleared meanwhile is never sent.
ALTER TABLE notification_log ADD COLUMN digest_id UUID;
-- coalesce: a row with no notice at all must compare as "not a digest", not as unknown (a CHECK passes on NULL).
ALTER TABLE notification_log ADD CONSTRAINT notification_log_digest_whole
    CHECK ((coalesce(alert_notice, '') = 'DIGEST') = (digest_id IS NOT NULL));
CREATE UNIQUE INDEX uq_notification_log_digest ON notification_log (digest_id, staff_to) WHERE digest_id IS NOT NULL;

CREATE TABLE below_cost_digest_piece (
    digest_id  UUID  NOT NULL,
    alert_id   UUID  NOT NULL REFERENCES staff_alert (id),
    PRIMARY KEY (digest_id, alert_id)
);
CREATE INDEX idx_below_cost_digest_piece_alert ON below_cost_digest_piece (alert_id);
