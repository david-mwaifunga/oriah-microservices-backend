-- Audit trail of every notification the service has "sent". Today the
-- delivery channel is just a log line, but the row layout is set up
-- for adding real channels (email, SMS, push) later.

CREATE TABLE notification_log (
    id              UUID            PRIMARY KEY,
    order_id        UUID,
    keycloak_id     VARCHAR(255)    NOT NULL,
    event_type      VARCHAR(60)     NOT NULL,
    channel         VARCHAR(20)     NOT NULL,
    subject         VARCHAR(255)    NOT NULL,
    body            TEXT            NOT NULL,
    delivered_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_log_keycloak ON notification_log(keycloak_id);
CREATE INDEX idx_notification_log_order    ON notification_log(order_id);
