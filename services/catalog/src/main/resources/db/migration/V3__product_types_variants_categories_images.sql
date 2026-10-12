-- Phase 7 -- the catalogue stops being a stub.
--
-- V1 gave us products(id, title, description) + product_tags. That is all the
-- catalogue has ever held: no price, no category, no type, no image, no
-- variant. The storefront compensated by hardcoding `unitPrice={100}` in the
-- PDP and letting cart-service accept whatever price the client sent, which
-- was demonstrated live to let anyone buy anything for 0.01.
--
-- ===========================================================================
-- THE ONE CHOICE THIS MIGRATION IS BUILT AROUND
-- ===========================================================================
-- Catalog's product id is currently used AS the variant id in five separate
-- databases:
--
--   inventory  inventory.variant_id      PRIMARY KEY, fed the product id
--              product_index.variant_id  PRIMARY KEY, same
--   cart       UNIQUE (cart_id, variant_id) -- variant_id IS the line identity
--   order      order_items.variant_id    -- a deliberately immutable snapshot
--   search     search_index.variant_id   PRIMARY KEY
--
-- There is no cross-database transaction available to us and order_items is a
-- financial record that must never be rewritten. So re-keying is off the
-- table, and this migration has exactly one safe shape: every pre-existing
-- product gets exactly ONE variant whose id IS the product's own id.
--
-- After that, `variantId == productId` remains TRUE for every row written
-- before this migration and becomes FALSE only for variants created
-- afterwards. Every existing cart line, order line, stock row and search row
-- keeps resolving, and nothing outside catalog is touched.
--
-- V3BackfillTest proves this by migrating to V2, inserting legacy-shaped
-- products, then migrating here -- the only way to get rows that predate a
-- migration, since Flyway otherwise runs everything before any test code sees
-- a connection.
--
-- ===========================================================================
-- WHY MIGRATED PRODUCTS BECOME DRAFT
-- ===========================================================================
-- A backfilled variant has no price anyone chose. Publishing it at 0.00 would
-- make the whole existing catalogue free; leaving it ACTIVE at 0.00 is the one
-- outcome this migration must not produce. So migrated products go to DRAFT
-- and the service layer refuses to publish a product without at least one
-- active variant priced above zero. The visible consequence is deliberate:
-- the storefront list is empty until someone prices these, which is correct,
-- because today it shows items it invents a price for.

-- ---------------------------------------------------------------------------
-- Product types. The ROWS live here so the FK holds and the admin dropdown can
-- enumerate them; the ATTRIBUTE DEFINITIONS live in Java
-- (ProductTypeRegistry), because they have to drive server-side validation,
-- the admin form AND the PDP spec table, and a second definition in the
-- database would be a second source of truth with no compile-time safety.
-- A startup check fails fast if this table ever holds a code Java does not
-- know.
-- ---------------------------------------------------------------------------
CREATE TABLE product_types (
    code         TEXT PRIMARY KEY,
    display_name TEXT    NOT NULL,
    position     INTEGER NOT NULL DEFAULT 0
);

INSERT INTO product_types (code, display_name, position) VALUES
    ('JEWELLERY', 'Jewellery', 0),
    ('WATCH',     'Watches',   1);

-- ---------------------------------------------------------------------------
-- Categories: explicit and hierarchical, which is what tags could never be.
-- Tags stay exactly as they are for cross-cutting labels ("new-in", "gift") --
-- the ask was for categories as well as tags, not instead of them.
--
-- The slug is GLOBALLY unique, not unique per parent. That is a decision, not
-- an oversight: URLs are flat /categories/{slug}, which is what a two-level
-- jewellery/watch taxonomy needs. The cost is that "rings" cannot exist under
-- two different parents. The alternative (path-based URLs plus full-path
-- uniqueness) is real complexity for a case this catalogue does not have.
--
-- The CHECK only catches self-parenting; deeper cycles (A -> B -> A) are
-- prevented by an ancestor walk in the service. Reads are safe REGARDLESS of
-- whether that holds, because the descendant query carries both a depth cap
-- and a visited-path guard -- a cycle introduced by direct SQL cannot hang a
-- storefront request. CategoryRepositoryTest seeds a cycle directly, bypassing
-- the service, to prove that.
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
    id          UUID PRIMARY KEY,
    parent_id   UUID         REFERENCES categories(id),
    slug        TEXT         NOT NULL UNIQUE,
    name        TEXT         NOT NULL,
    description TEXT,
    position    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT categories_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX idx_categories_parent ON categories(parent_id);

-- ---------------------------------------------------------------------------
-- New product columns.
--
-- Added with a DEFAULT so the existing rows get a value, then the default is
-- DROPPED so subsequent inserts must come from the application -- the same
-- pattern V2 used for the audit columns, and for the same reason: a database
-- default silently covering for a missing application value is a bug that
-- never surfaces.
-- ---------------------------------------------------------------------------
ALTER TABLE products ADD COLUMN product_type_code TEXT NOT NULL DEFAULT 'JEWELLERY';
ALTER TABLE products ALTER COLUMN product_type_code DROP DEFAULT;
ALTER TABLE products ADD CONSTRAINT fk_products_product_type
    FOREIGN KEY (product_type_code) REFERENCES product_types(code);

-- Type-specific specs: caseDiameterMm/movement/waterResistanceM for a watch,
-- metal/metalPurity/caratWeight for a ring. Validated in the service against
-- the registry (unknown key, missing required, value outside allowedValues are
-- each a 400 with fieldErrors), so this column never holds an unvalidated map.
ALTER TABLE products ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Which axes this product's variants vary on, in display order: {"Ring Size"},
-- or {"Strap","Case Size"}. A variant's option KEY SET must equal this
-- exactly; both empty is valid and is the one-off / default-variant case.
ALTER TABLE products ADD COLUMN option_names TEXT[] NOT NULL DEFAULT '{}';

ALTER TABLE products ADD COLUMN status TEXT NOT NULL DEFAULT 'DRAFT';
ALTER TABLE products ADD CONSTRAINT products_status_known
    CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'));

ALTER TABLE products ADD COLUMN slug TEXT;
ALTER TABLE products ADD COLUMN primary_category_id UUID REFERENCES categories(id);

-- Backfill slugs from titles. Two edge cases handled rather than discovered:
-- a title whose characters all strip away (punctuation only) falls back to the
-- id, and two products with the same title get the loser's id appended, so the
-- UNIQUE constraint below can be added without a chance of failing on real
-- data.
WITH base AS (
    SELECT id,
           nullif(trim(both '-' from regexp_replace(lower(title), '[^a-z0-9]+', '-', 'g')), '') AS candidate
    FROM products
), numbered AS (
    SELECT id,
           coalesce(candidate, 'product') AS candidate,
           row_number() OVER (PARTITION BY coalesce(candidate, 'product') ORDER BY id) AS rn
    FROM base
)
UPDATE products p
SET slug = CASE WHEN n.rn = 1 AND n.candidate <> 'product'
                THEN n.candidate
                ELSE n.candidate || '-' || left(replace(p.id::text, '-', ''), 8)
           END
FROM numbered n
WHERE n.id = p.id;

ALTER TABLE products ALTER COLUMN slug SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT products_slug_unique UNIQUE (slug);

CREATE INDEX idx_products_status ON products(status);
CREATE INDEX idx_products_type ON products(product_type_code);
CREATE INDEX idx_products_primary_category ON products(primary_category_id);

-- ---------------------------------------------------------------------------
-- Product <-> category, many to many: a watch belongs in "Watches" AND
-- "Gifts". products.primary_category_id picks the one used for breadcrumbs and
-- the canonical URL, and the service enforces that it is a MEMBER of this set
-- -- otherwise a breadcrumb can point at a category the product is not in.
-- That invariant is cross-row, so it is a tested service rule, not a CHECK.
-- ---------------------------------------------------------------------------
CREATE TABLE product_categories (
    product_id  UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, category_id)
);

CREATE INDEX idx_product_categories_category ON product_categories(category_id);

-- ---------------------------------------------------------------------------
-- Variants. The variant, not the product, carries money and SKU.
--
-- unit_price is NUMERIC(12,2) to match every other money column in the
-- platform (cart, order, payment, promotion all use exactly this). Minor-unit
-- integers were considered and rejected: four services and every money display
-- in the frontend treat these as major units, so the change would be breaking
-- across the platform for no benefit at this scale.
--
-- currency defaults to ZMW -- the brand prices in Zambian Kwacha, which has
-- two minor units, so the NUMERIC(12,2) shape holds unchanged. Rows recorded
-- before this migration keep whatever currency they already carry; nothing is
-- rewritten.
--
-- The fixed-width-looking columns (currency, option_digest, content_hash) are
-- VARCHAR, not CHAR. Postgres CHAR(n) is bpchar, which blank-pads and compares
-- with pad semantics, and Hibernate maps a String field to varchar -- so CHAR
-- here fails `ddl-auto=validate` with "found [bpchar], but expecting
-- [varchar(64)]". These values are always exactly their declared length
-- anyway, so varchar behaves identically with none of the padding surprises.
--
-- option_digest is SHA-256 over the canonical JSON of `options` (keys sorted
-- by code point, NFC-normalised, values trimmed). It exists so that "the same
-- option combination twice" is a database-level unique violation rather than
-- an application-level race between two admins. An earlier design used a
-- sorted `k=v|k=v` string, which breaks the moment a key or value contains
-- '=' or '|' or differs only by Unicode normalisation.
--
-- Variants are never physically deleted -- "delete" sets active = false. They
-- are identity-bearing across five databases where no foreign key can protect
-- them, so a hard delete is an operation catalog cannot verify is safe.
-- ---------------------------------------------------------------------------
CREATE TABLE product_variants (
    id            UUID PRIMARY KEY,
    product_id    UUID           NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku           TEXT           NOT NULL UNIQUE,
    unit_price    NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    currency      VARCHAR(3)     NOT NULL DEFAULT 'ZMW',
    options       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    option_digest VARCHAR(64)    NOT NULL,
    position      INTEGER        NOT NULL DEFAULT 0,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    created_by    VARCHAR(64),
    updated_by    VARCHAR(64),
    is_deleted    BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT product_variants_option_unique UNIQUE (product_id, option_digest)
);

CREATE INDEX idx_product_variants_product ON product_variants(product_id, position);
-- Backs "from $X" on the list and price-range filtering, which run on every
-- storefront grid render.
CREATE INDEX idx_product_variants_price ON product_variants(product_id, unit_price)
    WHERE active = TRUE;

-- THE IDENTITY-PRESERVING BACKFILL. `id` is the product's own id -- see the
-- header. sha256('{}') is computed here rather than pasted so it cannot drift
-- from what the Java canonicaliser produces for an empty option map.
INSERT INTO product_variants (id, product_id, sku, unit_price, currency, options, option_digest, position, active)
SELECT p.id,
       p.id,
       'LEGACY-' || upper(left(replace(p.id::text, '-', ''), 10)),
       0.00,
       'ZMW',
       '{}'::jsonb,
       encode(sha256('{}'::bytea), 'hex'),
       0,
       TRUE
FROM products p;

-- ---------------------------------------------------------------------------
-- Images. Postgres holds an OPAQUE STORAGE KEY and metadata, never a URL: an
-- absolute URL baked into a row is an environment-specific landmine the first
-- time the stack runs on a different host, and it is exactly what would force
-- a data migration rather than a URL-builder change if the read path ever
-- moves to a CDN.
--
-- content_hash is the SHA-256 of the STORED (re-encoded) bytes, so
-- UNIQUE (product_id, content_hash) makes re-uploading the same file
-- idempotent. The ETag served to clients comes from this column -- not from
-- the object store, which was measured returning no ETag at all on HeadObject.
--
-- Variant association is a JOIN TABLE rather than a nullable variant_id on
-- this row. With a column, one image could belong to at most one variant, so
-- reusing the same photograph for two variants of a product was impossible
-- while the UNIQUE above also forbade storing it twice.
-- ---------------------------------------------------------------------------
CREATE TABLE product_images (
    id           UUID PRIMARY KEY,
    product_id   UUID    NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    storage_key  TEXT    NOT NULL UNIQUE,
    content_hash VARCHAR(64) NOT NULL,
    content_type TEXT    NOT NULL,
    width        INTEGER NOT NULL CHECK (width > 0),
    height       INTEGER NOT NULL CHECK (height > 0),
    byte_size    BIGINT  NOT NULL CHECK (byte_size > 0),
    alt_text     TEXT,
    position     INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT product_images_content_unique UNIQUE (product_id, content_hash)
);

CREATE INDEX idx_product_images_product ON product_images(product_id, position);

CREATE TABLE product_variant_images (
    variant_id UUID    NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    image_id   UUID    NOT NULL REFERENCES product_images(id) ON DELETE CASCADE,
    position   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (variant_id, image_id)
);

CREATE INDEX idx_product_variant_images_image ON product_variant_images(image_id);
