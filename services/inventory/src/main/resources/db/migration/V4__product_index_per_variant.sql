-- Phase 7 -- product_index becomes genuinely variant-grained.
--
-- ===========================================================================
-- WHAT WAS ALREADY TRUE, AND WHAT STOPPED BEING TRUE
-- ===========================================================================
-- product_index.variant_id has always been a primary key fed from the catalog
-- PRODUCT id -- V2's own header says so: "The variant_id is the product UUID
-- until catalog grows a Variant entity (same convention as the existing
-- inventory table)."
--
-- Catalog has now grown one. A product can carry several purchasable variants,
-- each with its own id, and the storefront sells variants rather than
-- products. So the old convention is not merely inelegant any more, it is
-- wrong for anything created after catalog's V3: inventory would be holding a
-- stock reference for an id nobody can buy, while the actual variants had
-- none.
--
-- Catalog's V3 was built so that this migration does not have to re-key
-- anything. Every pre-existing product was given exactly one variant whose id
-- IS the product's id, so for every row already in this table, variant_id is
-- simultaneously a valid variant id and the product id. The backfill below is
-- therefore a copy, not a translation -- and crucially, no row's primary key
-- changes, so the inventory rows and cart and order lines that reference these
-- ids stay valid.

-- ---------------------------------------------------------------------------
-- Which product a variant belongs to.
--
-- Needed because product.deleted names a PRODUCT, and without this column
-- there is no way to find that product's variant rows. Nullable for exactly
-- as long as it takes to backfill, then NOT NULL.
-- ---------------------------------------------------------------------------
ALTER TABLE product_index ADD COLUMN IF NOT EXISTS product_id UUID;

-- The copy described above: correct by catalog's identity-preserving design,
-- not by coincidence.
UPDATE product_index SET product_id = variant_id WHERE product_id IS NULL;

ALTER TABLE product_index ALTER COLUMN product_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_product_index_product ON product_index(product_id);

-- ---------------------------------------------------------------------------
-- Whether the owning product has been deleted from the catalogue.
--
-- A FLAG rather than a delete, and that is the point of the column.
--
-- On product.deleted the obvious action is to drop these rows. That loses the
-- only title an operator has for stock that still physically exists: the
-- admin inventory list LEFT JOINs this table precisely so a stock row with no
-- catalog reference still appears, and after a delete it would show a bare
-- UUID and a quantity, which nobody can act on. Keeping the row unflagged is
-- no better -- the screen would show a live-looking product that is gone.
--
-- So the row survives and says so, and the admin list can render
-- "Aurelia 18k (deleted product)" with its real stock figure.
--
-- The inventory rows themselves are never touched by a catalog event. Stock
-- that physically exists does not stop existing because a catalogue entry was
-- withdrawn, and reconciling that is an operator's decision, not a consumer's.
-- ---------------------------------------------------------------------------
ALTER TABLE product_index ADD COLUMN IF NOT EXISTS product_deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_product_index_not_deleted
    ON product_index(variant_id) WHERE product_deleted = FALSE;
