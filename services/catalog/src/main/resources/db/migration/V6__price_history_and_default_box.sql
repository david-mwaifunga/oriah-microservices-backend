-- Phase 6: a price is dated history; a product has a default box; and a product remembers how often it was unpublished.
--
-- A PRICE IS NEVER OVERWRITTEN. A change adds a row from a date, so a paper sale keyed late can be priced at its true date.
-- product_variants.unit_price stays as the price IN FORCE NOW (the storefront's SQL and every event read it), written by the
-- service in the same transaction as the history row it comes from.
--
-- The history has NO foreign key to product_variants: it is a ledger of identities, like the outbox (a variant is never hard
-- deleted in production, and a ledger must not stop a test or an operator from clearing the table it points at).
--
-- currency is VARCHAR(3) and not CHAR(3): CHAR(n) is bpchar, which Hibernate's schema validation refuses for a String column
-- (V3 documents the failure).

CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TABLE variant_price (
    id             UUID PRIMARY KEY,
    -- Write order across the table. Two rows from the same instant are ordered by it, so a typo corrected is the price from
    -- the typo's own time.
    seq            BIGINT GENERATED ALWAYS AS IDENTITY,
    variant_id     UUID           NOT NULL,
    unit_price     NUMERIC(12, 2) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    -- When the price takes effect (what a sale is priced by), and when it was keyed (always now). A price keyed late has
    -- recorded_at after effective_from, and both are on the row.
    effective_from TIMESTAMPTZ    NOT NULL,
    recorded_at    TIMESTAMPTZ    NOT NULL,
    -- Who: user:<keycloak subject> for a person; system:migration for the rows made below. The name is whatever the token
    -- carried and may be empty: the subject is the record.
    set_by         VARCHAR(128)   NOT NULL,
    set_by_name    VARCHAR(100),
    source         VARCHAR(16)    NOT NULL,
    -- What the below-cost rule observed when this was saved. NOT_ASSESSED is for rows that were never compared with a cost
    -- (the migrated ones).
    assessment     VARCHAR(24)    NOT NULL,
    -- Why a price below cost (or in the wrong currency) was accepted, in the person's words.
    reason         VARCHAR(200),
    -- What the person was shown: the floor, the lots and their revisions, the default box, the currencies, when it was read.
    cost_basis     JSONB,
    CONSTRAINT variant_price_price_positive CHECK (unit_price > 0),
    CONSTRAINT variant_price_source_known CHECK (source IN ('SET', 'MIGRATED')),
    CONSTRAINT variant_price_assessment_known
        CHECK (assessment IN ('OK', 'BELOW', 'NO_COST_KNOWN', 'CURRENCY_MISMATCH', 'NOT_ASSESSED')),
    CONSTRAINT variant_price_reason_when_flagged
        CHECK (assessment NOT IN ('BELOW', 'CURRENCY_MISMATCH') OR (reason IS NOT NULL AND char_length(btrim(reason)) >= 5))
);

CREATE INDEX variant_price_variant_order ON variant_price (variant_id, effective_from, seq);

CREATE TRIGGER variant_price_append_only_row
    BEFORE UPDATE OR DELETE ON variant_price
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

-- A row trigger never fires for TRUNCATE, so TRUNCATE needs its own.
CREATE TRIGGER variant_price_append_only_truncate
    BEFORE TRUNCATE ON variant_price
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- Every variant that is already priced gets the one row that says so. Marked MIGRATED, so nobody reads it as a price somebody
-- set against a cost. A variant at 0.00 was never priced (V3's marker) and gets no row. updated_at is a TIMESTAMP with no zone,
-- written in UTC; a variant with neither date takes effect from now.
INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment)
SELECT gen_random_uuid(),
       v.id,
       v.unit_price,
       v.currency,
       COALESCE(COALESCE(v.updated_at, v.created_at) AT TIME ZONE 'UTC', now()),
       now(),
       'system:migration',
       'MIGRATED',
       'NOT_ASSESSED'
  FROM product_variants v
 WHERE v.unit_price > 0;

-- The box a product normally goes out in. No foreign key (it points at a variant of a PACKAGING product, and the catalogue
-- service checks that); none means the product has no default box.
ALTER TABLE products ADD COLUMN default_packaging_variant_id UUID;

-- How many times the product has been taken off sale after being published. Inventory releases the boxes it set aside for a
-- product when this goes up: a count that only grows is something it can compare without depending on the ORDER snapshots arrive.
ALTER TABLE products ADD COLUMN unpublish_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD CONSTRAINT products_unpublish_count_nonnegative CHECK (unpublish_count >= 0);
