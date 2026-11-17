-- Phase 13: payments through ZynlePay (mobile money and cards).
--
-- Staff who look after money are told when ZynlePay did not settle a payment (it has not answered, or answered something the system does not act
-- on by itself): the customer may have paid, and nobody else would find out. The alert ends when the payment is settled, by ZynlePay's answer or
-- by the owner's decision.

-- A new alert, and one new way an alert ends.
ALTER TABLE staff_alert DROP CONSTRAINT staff_alert_kind_known;
ALTER TABLE staff_alert ADD CONSTRAINT staff_alert_kind_known CHECK (kind IN
    ('PAYMENT_TO_CONFIRM', 'SETTLEMENT_STUCK', 'VOID_AFTER_SALE', 'CLAIM_EMAIL_LIMIT', 'EMAIL_DOWN',
     'BELOW_COST', 'BELOW_COST_CHECK_DOWN', 'REFUND_TO_PAY', 'PROVIDER_PAYMENT_UNRESOLVED'));
ALTER TABLE staff_alert DROP CONSTRAINT staff_alert_resolved_how_known;
ALTER TABLE staff_alert ADD CONSTRAINT staff_alert_resolved_how_known CHECK (resolved_how IS NULL OR resolved_how IN
    ('PAID', 'EXPIRED', 'CANCELLED', 'MONEY_RECORDED', 'UNSETTLED', 'VOIDED', 'NO_LONGER_BELOW', 'RECOVERED',
     'BY_CLOCK', 'PAID_OUT', 'MONEY_NEVER_CAME', 'SALE_CORRECTED', 'PAYMENT_SETTLED'));
-- staff_alert_audience_by_kind (V8) is unchanged: PROVIDER_PAYMENT_UNRESOLVED is not a cost alert, so it is told to the money mailboxes.
