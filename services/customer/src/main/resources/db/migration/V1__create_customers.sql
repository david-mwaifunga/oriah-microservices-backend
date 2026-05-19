-- Customer profiles. One row per Keycloak user, lazy-created on first
-- POST /api/v1/customers/me. Identified by keycloak_id (the `sub`
-- claim) so cross-service joins use Keycloak's UUID as the natural key.

CREATE TABLE customers (
    id            UUID         PRIMARY KEY,
    keycloak_id   VARCHAR(255) NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL,
    first_name    VARCHAR(120) NOT NULL,
    last_name     VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Multiple addresses per customer (shipping + billing + others). The
-- 'is_default' boolean marks the one to pre-select at checkout; a
-- partial unique index would enforce one default per customer, but
-- we keep that as application-level invariant for now.

CREATE TABLE addresses (
    id            UUID         PRIMARY KEY,
    customer_id   UUID         NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    label         VARCHAR(60)  NOT NULL,
    line1         VARCHAR(255) NOT NULL,
    line2         VARCHAR(255),
    city          VARCHAR(120) NOT NULL,
    state         VARCHAR(120),
    postal_code   VARCHAR(20)  NOT NULL,
    country       VARCHAR(2)   NOT NULL,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_addresses_customer ON addresses(customer_id);
