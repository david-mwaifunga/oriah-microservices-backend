-- Order is the immutable record of "customer X bought N items for Y
-- dollars" once a cart converts via checkout. Items are snapshotted
-- (variant_id, title, qty, unit price) at place time so the order is
-- meaningful even if catalog later removes a product.
--
-- The status column is the FSM state; transitions are validated by
-- OrderStatus.canTransitionTo(...) in the domain layer.

CREATE TABLE orders (
    id              UUID            PRIMARY KEY,
    keycloak_id     VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    subtotal        NUMERIC(12, 2)  NOT NULL CHECK (subtotal >= 0),
    -- shipping_cost and discount land later (Phase 2). For now total = subtotal.
    total           NUMERIC(12, 2)  NOT NULL CHECK (total >= 0),
    currency        VARCHAR(3)      NOT NULL,
    payment_intent_id UUID,
    shipping_address_id UUID,
    placed_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_keycloak ON orders(keycloak_id);
CREATE INDEX idx_orders_status   ON orders(status);

CREATE TABLE order_items (
    id            UUID            PRIMARY KEY,
    order_id      UUID            NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    variant_id    UUID            NOT NULL,
    title         VARCHAR(255)    NOT NULL,
    quantity      INT             NOT NULL CHECK (quantity > 0),
    unit_price    NUMERIC(12, 2)  NOT NULL CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order ON order_items(order_id);
