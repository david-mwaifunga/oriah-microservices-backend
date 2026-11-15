-- Phase 10 (finance): what the shop's own price for the delivery was, beside what staff agreed by phone.
--
-- A delivery agreed by phone is written once (V13: the agreed amount and why it differs). Finance reports what was conceded against the shop's quote, so the quote
-- is kept too, at the moment of a LIVE phone sale (a published price is today's: a late paper sale has none, and neither has a town the shop does not price).
-- Written once with the order and never changed; null on every order recorded before this column, which finance reports as "not recorded".

ALTER TABLE orders ADD COLUMN delivery_quoted_amount NUMERIC(12, 2);
ALTER TABLE orders ADD CONSTRAINT orders_delivery_quote_is_agreed
    CHECK (delivery_quoted_amount IS NULL OR (delivery_price_source = 'AGREED' AND delivery_quoted_amount >= 0));
