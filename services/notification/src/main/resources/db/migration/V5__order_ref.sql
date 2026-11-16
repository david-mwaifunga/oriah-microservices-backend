-- What notification remembers about an order, so that a later message can name it the way a person would
-- (the receipt number once it is paid), so that a message can be held back once the order was paid after
-- all, and so that a later fact that names only the order can find whose order it is.
--
-- Nothing personal: keycloak_id is the id every web order event already carries. No amount is kept: a
-- total can change without an event (a delivery waiver), so no message prints a remembered one.
CREATE TABLE order_ref (
    order_id         UUID          PRIMARY KEY,
    keycloak_id      VARCHAR(255),
    phone_order      BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Only for a phone order, as order itself says it on the event (saleKind): TRUE = taken live,
    -- FALSE = typed in later from paper. NULL = no event said: no words are ever written for it.
    live_phone_sale  BOOLEAN,
    receipt_number   VARCHAR(20),
    -- When notification first heard that the order was paid, from ANY order.paid (a repeated one too).
    paid_at          TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT order_ref_owner_by_channel    CHECK (NOT (phone_order AND keycloak_id IS NOT NULL)),
    CONSTRAINT order_ref_live_only_for_phone CHECK (live_phone_sale IS NULL OR phone_order)
);
