-- Phase 12 (review fix): what payment did about a refund order announced.
--
-- Order records what is owed for a correction and payment pays it. Order needs to know ONE thing back: is the refund still owed? While it is, the person
-- who pays refunds may read a phone order's caller in full (a caller has no account: the phone on the order is the only way to return their money); once
-- it is paid, or closed because the money never arrived, the phone is withheld again like anyone else's.
--
-- So order keeps payment's three facts about a debt, as they arrive, append-only. A debt's id is the correction's id. "Still owed" is derived, in any
-- order of arrival: not closed, and no payout that was not cancelled afterwards. No amount, no method, no reference: order does not need them.
--
-- No foreign key to order_correction: these are another service's facts and are kept whatever order holds.

CREATE TABLE order_refund_fact (
    correction_id UUID         NOT NULL,             -- payment's debt id, which is order's correction id
    kind          VARCHAR(16)  NOT NULL,
    payout_id     UUID,                              -- the payout paid or cancelled; none for a closed debt
    occurred_at   TIMESTAMPTZ  NOT NULL,             -- when payment says it happened
    recorded_at   TIMESTAMPTZ  NOT NULL,             -- when order heard of it
    CONSTRAINT order_refund_fact_kind_known CHECK (kind IN ('PAID_OUT', 'PAYOUT_VOIDED', 'CLOSED')),
    CONSTRAINT order_refund_fact_payout CHECK ((kind = 'CLOSED' AND payout_id IS NULL) OR (kind <> 'CLOSED' AND payout_id IS NOT NULL))
);
-- Each fact once (the Inbox applies an event once; this holds it whoever writes).
CREATE UNIQUE INDEX uq_order_refund_fact ON order_refund_fact (correction_id, kind, COALESCE(payout_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE TRIGGER order_refund_fact_append_only_row BEFORE UPDATE OR DELETE ON order_refund_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER order_refund_fact_append_only_truncate BEFORE TRUNCATE ON order_refund_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
