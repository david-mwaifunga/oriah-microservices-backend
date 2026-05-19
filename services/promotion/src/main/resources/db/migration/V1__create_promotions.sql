-- A Promotion is a redeemable discount code. The discount is either a
-- flat amount off (FIXED) or a percentage of subtotal (PERCENTAGE).
-- max_redemptions=0 means unlimited; valid_until=NULL means open-ended.

CREATE TABLE promotions (
    id                   UUID            PRIMARY KEY,
    code                 VARCHAR(40)     NOT NULL UNIQUE,
    type                 VARCHAR(20)     NOT NULL,
    amount               NUMERIC(12, 2)  NOT NULL CHECK (amount > 0),
    description          VARCHAR(255),
    valid_from           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    valid_until          TIMESTAMPTZ,
    max_redemptions      INT             NOT NULL DEFAULT 0,
    current_redemptions  INT             NOT NULL DEFAULT 0,
    enabled              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_promotions_code ON promotions(code);
