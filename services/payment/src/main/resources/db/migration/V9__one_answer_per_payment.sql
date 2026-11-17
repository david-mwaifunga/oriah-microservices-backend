-- Phase 12 (review fixes).
--
-- 1. Whether the money arrived is a fact about the PAYMENT, not about one debt. A sale paid by a money record that was cancelled afterwards can be
--    corrected more than once (two returns of one order), so it can have several debts in question. The owner's answer is given once and holds for
--    every one of them: two debts out of one payment can never carry opposite answers (one refunded as "it arrived", one closed as "it never came").
--    The service applies the answer to every debt of the payment; this is the wall behind it.

CREATE FUNCTION refund_due_check_one_answer_per_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1
                 FROM refund_due_check c
                 JOIN refund_due theirs ON theirs.id = c.refund_due_id
                 JOIN refund_due mine ON mine.id = NEW.refund_due_id
                WHERE theirs.confirmation_id = mine.confirmation_id
                  AND c.money_arrived <> NEW.money_arrived) THEN
        RAISE EXCEPTION 'refund_due_check: the money of one payment arrived or it did not: another debt out of the same payment was answered the other way';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER refund_due_check_one_answer BEFORE INSERT ON refund_due_check FOR EACH ROW EXECUTE FUNCTION refund_due_check_one_answer_per_payment();

-- 2. A payout that stands and was not cash always holds the key that keeps its reference to ONE payout. V8 required the key to equal the reference
--    when present, but let it be absent, and the unique index skips an absent key: a second row written past the service could then reuse a
--    reference. (A cancelled payout has let go of its debt and of its key together, so it is the standing ones that must carry it.)
ALTER TABLE money_out ADD CONSTRAINT money_out_live_reference_is_keyed
    CHECK (method = 'CASH' OR live_for IS NULL OR reference_key IS NOT NULL);
