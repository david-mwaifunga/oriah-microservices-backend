-- Phase 3: what a catalog entry is FOR.
--
--   SELLABLE   something Oriah sells to a shopper: a ring, a watch.
--   PACKAGING  what a sale goes out in: a box, a pouch, a ribbon. It is bought,
--              stocked and costed like any other item, so a consignment line has
--              a catalog entry to point at -- but it is never listed on the
--              storefront and has no selling price.
--
-- Every product that exists today was made to be sold, so every existing row
-- becomes SELLABLE. The default is then dropped (the pattern V3 used for
-- product_type_code): from here on a writer must say what it is making, instead
-- of an omitted value quietly meaning "sellable".
ALTER TABLE products ADD COLUMN kind TEXT NOT NULL DEFAULT 'SELLABLE';
ALTER TABLE products ALTER COLUMN kind DROP DEFAULT;

ALTER TABLE products ADD CONSTRAINT products_kind_known
    CHECK (kind IN ('SELLABLE', 'PACKAGING'));

-- Packaging has a product type of its own, with no spec fields: a box has no
-- metal or movement, and typing it JEWELLERY or WATCH would store it as
-- something it is not. Kind and type say the same thing about a row, so the
-- database holds them together: PACKAGING exactly when the type is PACKAGING.
INSERT INTO product_types (code, display_name, position) VALUES ('PACKAGING', 'Packaging', 2);

ALTER TABLE products ADD CONSTRAINT products_packaging_type_matches_kind
    CHECK ((kind = 'PACKAGING') = (product_type_code = 'PACKAGING'));

-- A packaging item is never listed. Product.publish() is the only code that
-- makes a product ACTIVE and it refuses packaging; this is the same rule held
-- by the database, so a direct SQL edit or a future code path cannot put a box
-- on the storefront, into the price the cart and checkout trust, or into search.
-- DRAFT and ARCHIVED packaging are fine: it is made and retired like anything else.
ALTER TABLE products ADD CONSTRAINT products_packaging_never_listed
    CHECK (kind = 'SELLABLE' OR status <> 'ACTIVE');
