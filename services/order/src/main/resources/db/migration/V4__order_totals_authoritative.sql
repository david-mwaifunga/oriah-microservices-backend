-- The order's own total becomes authoritative (MVP_PLAN Phase 0, item 4.1).
--
-- orders had no shipping or discount column, and Order.recomputeTotals()
-- ended "total = subtotal", so the order disagreed with what checkout
-- charged -- and with no amount check on payment, an order could be paid for
-- less than its total.
--
-- The new amounts are NULLABLE with NO BACKFILL: on an order placed before
-- V4, NULL means "unknown", never a claim that shipping or discount was zero.
-- totals_authoritative marks the orders placed from V4 on; for those, and
-- only those, the database itself enforces the formula, so no code path can
-- store a total that is not subtotal + shipping - discount.
ALTER TABLE orders
    ADD COLUMN shipping_amount      NUMERIC(12, 2)
        CONSTRAINT orders_shipping_amount_check CHECK (shipping_amount >= 0),
    ADD COLUMN discount_amount      NUMERIC(12, 2)
        CONSTRAINT orders_discount_amount_check CHECK (discount_amount >= 0),
    -- The option the shopper chose: SELF_COLLECT now, a code per town and
    -- option from Phase 7. Same shape the shared Quote accepts.
    ADD COLUMN delivery_option      VARCHAR(64)
        CONSTRAINT orders_delivery_option_format_chk CHECK (delivery_option ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    ADD COLUMN totals_authoritative BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE orders ADD CONSTRAINT orders_totals_authoritative_chk CHECK (
    NOT totals_authoritative OR (
        shipping_amount IS NOT NULL
        AND discount_amount IS NOT NULL
        AND delivery_option IS NOT NULL
        AND total = subtotal + shipping_amount - discount_amount
    )
);