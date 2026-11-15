-- Phase 9: a price from BEFORE the price in force.
--
-- A paper sale made before a piece's first price was entered cannot be recorded (a sale is never priced at today's price) and a price may start at most 72
-- hours back. The person who may back-date a sale (BACKDATE_SALE) can add an EARLIER row to the history, with a reason; it changes nothing about today's price
-- (the history is ordered by the date a price takes effect). The reason is kept on the row: NULL on every ordinary price, 5 to 200 characters on a historical one.
ALTER TABLE variant_price ADD COLUMN backdate_reason VARCHAR(200);
ALTER TABLE variant_price ADD CONSTRAINT variant_price_backdate_reason_length CHECK (backdate_reason IS NULL OR char_length(btrim(backdate_reason)) BETWEEN 5 AND 200);
