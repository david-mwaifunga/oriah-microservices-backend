-- Phase 13: the first card path is retired.
--
-- It took a card number and answered at once, which fits neither a prompt on a phone nor ZynlePay's own card page (and Oriah must never see a
-- card number). Phase 7 switched it off; nothing has gone live, so its tables hold only developers' data. Payments through ZynlePay are
-- provider_payment (V11), and their money is a payment_confirmation like any other.
--
-- What the path wrote to the outbox (payment.succeeded, payment.failed, aggregate payment_intent) STAYS: an event is a fact, the outbox is
-- append-only, and every reader ignores those two types for good.

-- V5 made these tables refuse a delete or a truncate; a table that is dropped takes its triggers with it.
DROP TABLE IF EXISTS fake_gateway_charge;
DROP TABLE IF EXISTS idempotency_keys;
DROP TABLE IF EXISTS payment_intents;
