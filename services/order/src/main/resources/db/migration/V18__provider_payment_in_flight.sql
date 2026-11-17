-- Phase 13: an order remembers the payment through ZynlePay that is in flight for it.
--
-- Before payment asks ZynlePay for any money it asks the order to take the attempt. While this column names an attempt, nothing else may pay or
-- end the order: no cancel (shopper or staff), no "I paid another way" claim, no free delivery, no second attempt. It has NO clock: it is let go
-- when payment says the attempt FAILED, or when money is being applied to the order, and an attempt ZynlePay never answers keeps the order closed
-- to other money until the owner decides it. The pieces' hold still lapses by its own time: an order may EXPIRE carrying the attempt.
ALTER TABLE orders ADD COLUMN provider_payment_id UUID;

CREATE INDEX orders_provider_payment ON orders (provider_payment_id) WHERE provider_payment_id IS NOT NULL;
