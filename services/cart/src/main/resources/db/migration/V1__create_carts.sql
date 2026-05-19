-- One cart per Keycloak user, identified by the JWT sub claim. Lazily
-- created on first add-to-cart. We don't model an "anonymous cart"
-- merge-on-login flow yet.
CREATE TABLE carts (
    id            UUID         PRIMARY KEY,
    keycloak_id   VARCHAR(255) NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per (cart, variant) tuple. Same variant added twice bumps
-- the quantity rather than inserting a duplicate row (application-side
-- via UNIQUE on (cart_id, variant_id)).
CREATE TABLE cart_items (
    id            UUID            PRIMARY KEY,
    cart_id       UUID            NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    variant_id    UUID            NOT NULL,
    quantity      INT             NOT NULL CHECK (quantity > 0),
    unit_price    NUMERIC(12, 2)  NOT NULL CHECK (unit_price >= 0),
    title         VARCHAR(255)    NOT NULL,
    added_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    UNIQUE (cart_id, variant_id)
);

CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);
