-- procurement V1: suppliers -- who Oriah buys from.
--
-- CLIENT_DECISIONS 4.1: a supplier is recorded once, with "name, country, who you
-- deal with there, how you pay them". Nothing is seeded: the owner types the real
-- list in herself.
--
-- All the audit columns and the optimistic-lock version are in V1 (promotion added
-- its audit columns in a later ALTER, which left mixed column types behind).
--
-- country is VARCHAR(2), not CHAR(2): Hibernate maps a String to varchar, and
-- PostgreSQL's char(n) is "bpchar", which fails ddl-auto=validate at startup
-- (catalog V3 records the same trap). The check is what keeps it to two capital
-- letters; the aggregate further limits it to real ISO 3166-1 alpha-2 codes.
CREATE TABLE suppliers (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    country         VARCHAR(2)   NOT NULL,
    -- "who you deal with there", and how to reach them: free text, both optional.
    contact_name    VARCHAR(120),
    contact_details VARCHAR(500),
    -- "how you pay them": a DESCRIPTION, not a payment record. There is no
    -- supplier-payments ledger (CLIENT_DECISIONS section 6, Q1).
    payment_notes   VARCHAR(500),
    -- inactive = a real supplier we have stopped using (is_deleted below is for
    -- correcting a record that should never have existed).
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_suppliers_country CHECK (country ~ '^[A-Z]{2}$')
);

-- Two LIVE suppliers cannot share a name, in any letter case. Partial, so a
-- deleted supplier frees its name; the service maps exactly this index's
-- violation to a 409 and leaves every other integrity failure a 500.
CREATE UNIQUE INDEX uq_suppliers_name_live ON suppliers (lower(name)) WHERE NOT is_deleted;
