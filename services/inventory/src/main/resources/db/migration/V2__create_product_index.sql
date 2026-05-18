-- product_index tracks which products inventory has heard about from
-- catalog (via the catalog.product Kafka topic). Inventory uses this in
-- a future cycle to reject reserve() / restock() against unknown
-- variantIds — today the reserve path doesn't check it, but the table
-- exists so the consumer can populate it from day one.
--
-- The variant_id is the product UUID until catalog grows a Variant
-- entity (same convention as the existing inventory table). Primary
-- key on variant_id gives us free idempotency: a replayed
-- product.created event upserts harmlessly.
CREATE TABLE product_index (
    variant_id  UUID         PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
