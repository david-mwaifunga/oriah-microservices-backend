-- Phase 13: money a payment provider collected (ZynlePay: mobile money and cards) names its provider.
--
-- The method stays the wallet or CARD: that is how the customer paid. The provider is who collected it, and so whose statement its fee is on:
-- the fees of MTN, Airtel, Zamtel and card payments that came through ZynlePay are all on ZynlePay's one statement, while the fee on MTN money
-- staff confirmed themselves is on MTN's.
--
-- A table of its own, one row per confirmation, written once: confirmation_fact is append-only, and which announcement of a payment arrives
-- first (one that names the provider, or one that does not) must not decide what finance knows. Money a person confirmed has no row.
CREATE TABLE confirmation_provider (
    confirmation_id UUID        PRIMARY KEY,
    provider        VARCHAR(16) NOT NULL,
    source_event_id UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT confirmation_provider_shape CHECK (provider ~ '^[A-Z]{2,16}$')
);

CREATE FUNCTION confirmation_provider_refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'confirmation_provider is written once: % refused', TG_OP;
END $$;

CREATE TRIGGER confirmation_provider_written_once BEFORE UPDATE OR DELETE ON confirmation_provider
    FOR EACH ROW EXECUTE FUNCTION confirmation_provider_refuse_change();
