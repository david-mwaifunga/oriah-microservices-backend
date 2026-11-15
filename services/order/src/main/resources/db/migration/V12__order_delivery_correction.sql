-- Phase 8 (slice 8.4d): a correction of who a paid parcel is for and where it goes, before it is packed.
--
-- The customer rings to say the number or the street was wrong. An order administrator (never a packer: one who could redirect a paid parcel could send it
-- to an accomplice) may replace the recipient and the address of a PAID order that is not yet packed, with a reason. The order's current columns (V10) hold the
-- new details; this table keeps what they were, who changed them, when and why. Append-only, like every record of a change to a sale. No event is
-- published for a correction: personal data never goes into the outbox.
--
-- Personal data is kept here on purpose, as the audit of an address change; how long is an owner question (the repository has no retention policy).

CREATE TABLE order_delivery_correction (
    id                       UUID PRIMARY KEY,
    order_id                 UUID         NOT NULL,
    seq                      INTEGER      NOT NULL,           -- 1 for the first correction of the order, 2 for the second ...
    previous_recipient_name  VARCHAR(120),
    previous_recipient_phone VARCHAR(16),
    previous_address_line1   VARCHAR(255),
    previous_address_line2   VARCHAR(255),
    previous_address_city    VARCHAR(120),
    previous_address_state   VARCHAR(120),
    previous_address_postal_code VARCHAR(20),
    previous_address_country VARCHAR(2),
    reason                   VARCHAR(200) NOT NULL,
    corrected_by             VARCHAR(128) NOT NULL,           -- user:<keycloak subject>
    corrected_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT order_delivery_correction_once UNIQUE (order_id, seq),
    CONSTRAINT order_delivery_correction_reason_length CHECK (char_length(btrim(reason)) BETWEEN 5 AND 200)
);

CREATE FUNCTION order_delivery_correction_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_delivery_correction is append-only: % is not allowed', TG_OP USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER order_delivery_correction_append_only_row
    BEFORE UPDATE OR DELETE ON order_delivery_correction
    FOR EACH ROW EXECUTE FUNCTION order_delivery_correction_append_only();

CREATE TRIGGER order_delivery_correction_append_only_truncate
    BEFORE TRUNCATE ON order_delivery_correction
    FOR EACH STATEMENT EXECUTE FUNCTION order_delivery_correction_append_only();
