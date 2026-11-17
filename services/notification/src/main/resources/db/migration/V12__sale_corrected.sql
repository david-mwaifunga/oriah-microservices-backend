-- Phase 12 (review fix): notification remembers that the sale a payment record paid was corrected.
--
-- "A cancelled payment record paid a sale anyway" (VOID_AFTER_SALE) is ended when the order system corrects that sale (a paid cancellation or a
-- return). The two facts travel on different topics, so the correction can be heard BEFORE the void: there was then no alert to end, and the void
-- raised one afterwards that nothing ended but the clock. With the correction remembered here, a void heard later raises nothing.

ALTER TABLE confirmation_watch ADD COLUMN corrected_at TIMESTAMPTZ;
