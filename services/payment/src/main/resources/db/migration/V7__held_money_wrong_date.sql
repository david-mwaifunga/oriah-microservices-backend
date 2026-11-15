-- Phase 9: order holds money confirmed for a late paper sale on another day than the sale happened (WRONG_DATE): payment owes it back to the caller until
-- staff void it and key it again with the day the sale happened. A new reason beside the five order could already give.
ALTER TABLE held_money DROP CONSTRAINT held_money_reason_known;
ALTER TABLE held_money ADD CONSTRAINT held_money_reason_known
    CHECK (reason IN ('WRONG_AMOUNT', 'ORDER_CANCELLED', 'PIECE_SOLD', 'ALREADY_PAID', 'ORDER_UNKNOWN', 'WRONG_DATE'));
