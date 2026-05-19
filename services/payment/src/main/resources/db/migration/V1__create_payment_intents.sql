-- A PaymentIntent is a "we plan to charge this much from this customer
-- for this order" object. It starts in PENDING; when the client calls
-- /confirm with their card details, the gateway transitions it to
-- SUCCEEDED (or FAILED). This mirrors Stripe's PaymentIntent model so
-- the FakePaymentGateway can later be swapped for the real Stripe SDK.

CREATE TABLE payment_intents (
    id              UUID            PRIMARY KEY,
    order_id        UUID            NOT NULL,
    keycloak_id     VARCHAR(255)    NOT NULL,
    amount          NUMERIC(12, 2)  NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)      NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    confirmed_at    TIMESTAMPTZ
);

CREATE INDEX idx_payment_intents_order ON payment_intents(order_id);
CREATE INDEX idx_payment_intents_keycloak ON payment_intents(keycloak_id);
