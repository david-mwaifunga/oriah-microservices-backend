-- Phase 7 (slice 7.5): a box changed AFTER the order was paid.
--
-- The customer asks for a different box, or the one set aside is damaged. Inventory does the stock (it reverses the original box's sale and sells the
-- replacement, both dated to the day of the SALE); order keeps the record of WHO changed WHICH line's box, WHEN and WHY. Append-only, like every record of
-- a change to a sale: a correction is a new row that points back at the one before it (the second swap of a line starts from the box the first sold).
--
-- No foreign key to orders or order_items: the amendment is a fact about a sale, and it must outlive anything that might one day tidy those tables.
-- The amendment id is minted by order (or sent by the caller, to retry safely) and is the idempotency key shared with inventory.

CREATE TABLE order_box_amendment (
    id                 UUID PRIMARY KEY,                       -- the amendment id: one swap happens once
    order_id           UUID         NOT NULL,
    item_id            UUID         NOT NULL,                  -- the order line whose box changed
    product_id         UUID         NOT NULL,
    old_box_variant_id UUID         NOT NULL,
    new_box_variant_id UUID         NOT NULL,
    old_box_line_id    UUID         NOT NULL,                  -- the sale line the old box was sold under
    new_box_line_id    UUID         NOT NULL,                  -- the sale line the replacement is sold under (derived from the amendment id)
    quantity           INTEGER      NOT NULL,                  -- one box per piece
    reason             VARCHAR(200) NOT NULL,
    amended_by         VARCHAR(128) NOT NULL,
    occurred_at        TIMESTAMPTZ  NOT NULL,                  -- the day of the sale: a swap never moves it
    recorded_at        TIMESTAMPTZ  NOT NULL,                  -- when the swap was made
    CONSTRAINT order_box_amendment_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_box_amendment_changes_the_box CHECK (old_box_variant_id <> new_box_variant_id),
    CONSTRAINT order_box_amendment_reason_length CHECK (char_length(btrim(reason)) BETWEEN 10 AND 200)
);

CREATE INDEX order_box_amendment_order ON order_box_amendment (order_id, recorded_at);

CREATE FUNCTION order_box_amendment_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_box_amendment is append-only: % is not allowed', TG_OP USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER order_box_amendment_append_only_row
    BEFORE UPDATE OR DELETE ON order_box_amendment
    FOR EACH ROW EXECUTE FUNCTION order_box_amendment_append_only();

CREATE TRIGGER order_box_amendment_append_only_truncate
    BEFORE TRUNCATE ON order_box_amendment
    FOR EACH STATEMENT EXECUTE FUNCTION order_box_amendment_append_only();
