-- One review per (customer, product) — enforces "you can only review
-- a product once" via UNIQUE. Rating is 1-5; body is the prose
-- review. We log the orderId the customer claims as proof of
-- purchase; future cycle adds a Feign call to order-service to verify
-- the order is in DELIVERED state.

CREATE TABLE reviews (
    id              UUID            PRIMARY KEY,
    product_id      UUID            NOT NULL,
    keycloak_id     VARCHAR(255)    NOT NULL,
    order_id        UUID            NOT NULL,
    rating          SMALLINT        NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title           VARCHAR(120),
    body            TEXT,
    helpful_count   INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    UNIQUE (product_id, keycloak_id)
);

CREATE INDEX idx_reviews_product ON reviews(product_id);
CREATE INDEX idx_reviews_keycloak ON reviews(keycloak_id);
