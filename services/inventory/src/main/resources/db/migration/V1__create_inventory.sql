-- inventory schema: one row per variant tracking stock-on-hand and reservations.
--
-- variantId is the join key against the catalog's variants — kept loose here
-- (no FK) because catalog and inventory are separate services with separate
-- databases. Cross-service references go through the network at read time.

CREATE TABLE inventory (
    variant_id  UUID PRIMARY KEY,
    on_hand     INTEGER NOT NULL CHECK (on_hand >= 0),
    reserved    INTEGER NOT NULL CHECK (reserved >= 0),
    -- Invariant enforced in the domain layer too; redundant DB check is
    -- belt-and-suspenders for bad direct writes.
    CONSTRAINT reserved_le_on_hand CHECK (reserved <= on_hand)
);
