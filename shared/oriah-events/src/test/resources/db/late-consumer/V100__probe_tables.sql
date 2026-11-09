-- Test-only tables for the late-consumer probes (LateConsumerApplication).
-- Never copied by a service, and kept out of the test-jar.

-- The fact probe: one row per sale it applied. Deliberately no unique key,
-- so a fact applied twice shows up as two rows instead of being hidden by a
-- constraint violation.
CREATE TABLE probe_sale (
    event_id UUID        NOT NULL,
    sale_id  VARCHAR(64) NOT NULL,
    body     TEXT        NOT NULL
);

-- The projection probe: the newest snapshot of each product, and no row once
-- the product is deleted.
CREATE TABLE probe_product (
    product_id VARCHAR(64) PRIMARY KEY,
    seq        BIGINT      NOT NULL,
    body       TEXT        NOT NULL
);
