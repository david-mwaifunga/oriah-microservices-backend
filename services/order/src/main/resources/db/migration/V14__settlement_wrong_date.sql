-- Phase 9: money confirmed for a late paper sale on another day than the sale happened is held for the caller, not applied (reason WRONG_DATE).
--
-- A paper sale counts on the day it happened, and a month's profit is a count of days: if staff priced a sale for the 12th but confirmed its money on the 14th,
-- applying it would date the sale's revenue, its cost of goods and its packaging to the 14th while its receipt and price say the 12th. So the money is held
-- (payment owes it back to the caller until staff void it and key it again with the right day); the order stays as it was.
ALTER TABLE order_settlement DROP CONSTRAINT order_settlement_reason_known;
ALTER TABLE order_settlement ADD CONSTRAINT order_settlement_reason_known
    CHECK (reason IS NULL OR reason IN ('WRONG_AMOUNT', 'ORDER_UNKNOWN', 'ORDER_CANCELLED', 'ALREADY_PAID', 'PIECE_SOLD', 'VOIDED', 'WRONG_DATE'));
