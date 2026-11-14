-- Phase 7 (slice 7.6): free delivery for one order, for a chosen customer (CLIENT_DECISIONS Q2; design 3.4).
--
-- A member of staff holding DELIVERY_WAIVER sets the delivery of an order that is still waiting for a payment reference to K0, with a reason. The order's
-- shipping_amount becomes 0.00 and its total falls by exactly that (the V4 CHECK, total = subtotal + shipping - discount, still holds); what delivery WOULD
-- have cost is kept here, with who, why and when, because it is delivery revenue given up, never goods revenue, and finance will want the figure.
-- All four columns are set together or not at all.

ALTER TABLE orders ADD COLUMN delivery_waived_amount NUMERIC(12, 2);   -- what delivery would have cost
ALTER TABLE orders ADD COLUMN delivery_waived_by     VARCHAR(128);     -- user:<keycloak subject> of the member of staff
ALTER TABLE orders ADD COLUMN delivery_waiver_reason VARCHAR(200);
ALTER TABLE orders ADD COLUMN delivery_waived_at     TIMESTAMPTZ;

ALTER TABLE orders ADD CONSTRAINT orders_delivery_waiver_whole CHECK (
    (delivery_waived_amount IS NULL) = (delivery_waived_by IS NULL)
    AND (delivery_waived_by IS NULL) = (delivery_waiver_reason IS NULL)
    AND (delivery_waiver_reason IS NULL) = (delivery_waived_at IS NULL));
ALTER TABLE orders ADD CONSTRAINT orders_delivery_waived_positive CHECK (delivery_waived_amount IS NULL OR delivery_waived_amount > 0);
ALTER TABLE orders ADD CONSTRAINT orders_delivery_waiver_reason_length CHECK (
    delivery_waiver_reason IS NULL OR char_length(btrim(delivery_waiver_reason)) BETWEEN 5 AND 200);
