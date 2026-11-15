-- Phase 8 (slice 8.3): who a delivered parcel is for and where it goes, kept on the order as it was when it was placed.
--
-- The packer or the driver needs a name to ask for, a phone number to ring and an address to go to. None of it can be looked up later: a shopper can
-- delete an address, and a packer cannot read the customer's book. So order copies it at placement: the recipient from what the shopper typed, the address
-- lines from the address customer answered with, and the names of the option and the town shipping priced. A pickup keeps none of it.
--
-- All columns are nullable (an order placed before this migration, a pickup, and Phase 9's phone order, which may know only a town). The rules hold
-- whenever a value is present: a name comes with a phone, a phone is '+' and 8 to 15 digits, a name is not blank, and an address is whole.
-- Never in an event and never in a log: the outbox is append-only, so personal data put there could never be erased.

ALTER TABLE orders ADD COLUMN recipient_name        VARCHAR(120);
ALTER TABLE orders ADD COLUMN recipient_phone       VARCHAR(16);
ALTER TABLE orders ADD COLUMN address_line1         VARCHAR(255);
ALTER TABLE orders ADD COLUMN address_line2         VARCHAR(255);
ALTER TABLE orders ADD COLUMN address_city          VARCHAR(120);
ALTER TABLE orders ADD COLUMN address_state         VARCHAR(120);
ALTER TABLE orders ADD COLUMN address_postal_code   VARCHAR(20);
ALTER TABLE orders ADD COLUMN address_country       VARCHAR(2);
ALTER TABLE orders ADD COLUMN delivery_option_name  VARCHAR(80);    -- what shipping called the option when it was priced, e.g. "Yango"
ALTER TABLE orders ADD COLUMN delivery_town_name    VARCHAR(120);

ALTER TABLE orders ADD CONSTRAINT orders_recipient_whole CHECK ((recipient_name IS NULL) = (recipient_phone IS NULL));
ALTER TABLE orders ADD CONSTRAINT orders_recipient_phone_shape CHECK (recipient_phone IS NULL OR recipient_phone ~ '^\+[0-9]{8,15}$');
ALTER TABLE orders ADD CONSTRAINT orders_recipient_name_not_blank CHECK (recipient_name IS NULL OR btrim(recipient_name) <> '');
ALTER TABLE orders ADD CONSTRAINT orders_address_whole CHECK (
    (address_line1 IS NULL) = (address_city IS NULL)
    AND (address_city IS NULL) = (address_country IS NULL)
    AND (address_line1 IS NOT NULL OR (address_line2 IS NULL AND address_state IS NULL AND address_postal_code IS NULL)));
