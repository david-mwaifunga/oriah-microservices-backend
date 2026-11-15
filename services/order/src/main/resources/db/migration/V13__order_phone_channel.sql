-- Phase 9: an order staff record for a caller with no account (a phone call, a WhatsApp chat, a sale written on paper and typed in later).
--
-- Until now an order always belonged to a signed-in shopper (keycloak_id NOT NULL). A caller has no account (CLIENT_DECISIONS Q24; guest checkout stays
-- out of scope), so the owner becomes nullable, but ONLY for the PHONE channel, and every rule that makes a phone order well-formed is the DATABASE's: no code
-- path, no future channel and no SQL run by hand can store an ownerless web order, a phone order with no recorder, or a delivery price that is not what it says.
--
-- What a phone order adds: who recorded it (recorded_by, from the token, never from the body), the caller (name always; phone for any delivery), the kind of
-- staff sale (LIVE_PHONE: dated now; LATE_PAPER: a paper sale at its typed true date, always with a reason), the date the sale happened (occurred_at; the order's
-- placed_at stays the keying time), an optional slip number, a hash of the request (so a repeat is recognised), and the delivery's price source: PUBLISHED (the
-- shop's town price, as on the website) or AGREED (keyed by staff for a delivery agreed by phone: the agreed amount and why it differs, written once; the live
-- charge, shipping_amount, can still be waived by the owner or a manager, which does not touch the agreement).
--
-- Every existing order is a WEB order with its owner: channel defaults to WEB, occurred_at is its placed_at, its delivery price was published.

ALTER TABLE orders ALTER COLUMN keycloak_id DROP NOT NULL;

ALTER TABLE orders ADD COLUMN channel                  VARCHAR(10)  NOT NULL DEFAULT 'WEB';
ALTER TABLE orders ADD COLUMN recorded_by              VARCHAR(128);
ALTER TABLE orders ADD COLUMN caller_name              VARCHAR(120);
ALTER TABLE orders ADD COLUMN caller_phone             VARCHAR(16);
ALTER TABLE orders ADD COLUMN sale_kind                VARCHAR(12);
ALTER TABLE orders ADD COLUMN occurred_at              TIMESTAMPTZ  NOT NULL DEFAULT now();
ALTER TABLE orders ADD COLUMN backdate_reason          VARCHAR(200);
ALTER TABLE orders ADD COLUMN slip_reference           VARCHAR(40);
ALTER TABLE orders ADD COLUMN request_hash             VARCHAR(64);
ALTER TABLE orders ADD COLUMN delivery_price_source    VARCHAR(10)  NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE orders ADD COLUMN delivery_agreed_amount   NUMERIC(12, 2);
ALTER TABLE orders ADD COLUMN delivery_agreed_reason   VARCHAR(200);

-- An order that exists today happened when it was placed.
UPDATE orders SET occurred_at = placed_at;

ALTER TABLE orders ADD CONSTRAINT orders_channel_known CHECK (channel IN ('WEB', 'PHONE'));

-- The owner follows the channel: a web order always has its shopper, a phone order never does.
ALTER TABLE orders ADD CONSTRAINT orders_owner_by_channel CHECK (
    (channel = 'WEB' AND keycloak_id IS NOT NULL AND btrim(keycloak_id) <> '')
    OR (channel = 'PHONE' AND keycloak_id IS NULL));

-- A web order carries nothing of a phone order; a phone order is whole (the caller's phone may be missing only for a pickup: a person at the counter).
ALTER TABLE orders ADD CONSTRAINT orders_phone_record CHECK (
    (channel = 'WEB'
        AND recorded_by IS NULL AND caller_name IS NULL AND caller_phone IS NULL AND sale_kind IS NULL AND request_hash IS NULL
        AND slip_reference IS NULL AND backdate_reason IS NULL AND delivery_price_source = 'PUBLISHED')
    OR (channel = 'PHONE'
        AND recorded_by IS NOT NULL AND caller_name IS NOT NULL AND sale_kind IS NOT NULL AND request_hash IS NOT NULL
        AND (caller_phone IS NOT NULL OR COALESCE(delivery_option, '') = 'SELF_COLLECT')));

ALTER TABLE orders ADD CONSTRAINT orders_caller_shape CHECK (
    (caller_phone IS NULL OR caller_phone ~ '^\+[0-9]{8,15}$')
    AND (caller_name IS NULL OR btrim(caller_name) <> ''));

ALTER TABLE orders ADD CONSTRAINT orders_request_hash_shape CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE orders ADD CONSTRAINT orders_sale_kind_known CHECK (sale_kind IS NULL OR sale_kind IN ('LIVE_PHONE', 'LATE_PAPER'));

-- A late paper sale always says why it is recorded late (what decides its price is a date the person chose); a live sale never does.
ALTER TABLE orders ADD CONSTRAINT orders_late_reason CHECK (
    (sale_kind IS NOT DISTINCT FROM 'LATE_PAPER') = (backdate_reason IS NOT NULL)
    AND (backdate_reason IS NULL OR char_length(btrim(backdate_reason)) BETWEEN 5 AND 200));

-- A sale cannot have happened after it was keyed (five minutes' allowance for a phone's clock).
ALTER TABLE orders ADD CONSTRAINT orders_occurred_not_future CHECK (occurred_at <= placed_at + interval '5 minutes');

ALTER TABLE orders ADD CONSTRAINT orders_delivery_source_known CHECK (delivery_price_source IN ('PUBLISHED', 'AGREED'));

-- An agreed delivery (keyed by staff, never by a customer): a phone order that delivers, to a town, with an amount that matches who pays the courier (a
-- recipient-paid courier is K0.00 on the order; Oriah's charge is more than K0.00) and, when staff said why it differs from the shop's quote, a reason of 5 to
-- 200 characters. A published price has none of these. No clause reads shipping_amount: a waiver (V8) lowers the live charge and leaves the agreement alone.
ALTER TABLE orders ADD CONSTRAINT orders_agreed_delivery CHECK (
    delivery_price_source NOT IN ('PUBLISHED', 'AGREED')
    OR (delivery_price_source = 'PUBLISHED' AND delivery_agreed_amount IS NULL AND delivery_agreed_reason IS NULL)
    OR (delivery_price_source = 'AGREED'
        AND channel = 'PHONE'
        AND COALESCE(delivery_option, 'SELF_COLLECT') <> 'SELF_COLLECT'
        AND delivery_town_id IS NOT NULL
        AND delivery_agreed_amount IS NOT NULL
        AND ((delivery_payer = 'RECIPIENT_PAYS' AND delivery_agreed_amount = 0) OR (delivery_payer = 'ORIAH_COLLECTS' AND delivery_agreed_amount > 0))
        AND (delivery_agreed_reason IS NULL OR char_length(btrim(delivery_agreed_reason)) BETWEEN 5 AND 200)));

-- A phone order that delivers has who it is for and where (Phase 8's recipient and address) until it is cancelled, which forgets them.
ALTER TABLE orders ADD CONSTRAINT orders_phone_delivery_whole CHECK (
    channel <> 'PHONE' OR status = 'CANCELLED' OR delivery_option = 'SELF_COLLECT'
    OR (recipient_name IS NOT NULL AND recipient_phone IS NOT NULL AND address_line1 IS NOT NULL));

-- One idempotency key is used once among phone orders, whoever recorded them (the web's (keycloak_id, key) index is inert for an order with no owner).
CREATE UNIQUE INDEX uq_orders_phone_idempotency ON orders (idempotency_key) WHERE channel = 'PHONE' AND idempotency_key IS NOT NULL;

-- Finding a recorded order: the newest first, by the caller's phone (exact), by the caller's name (prefix) and by a slip number.
CREATE INDEX idx_orders_phone_recent ON orders (placed_at DESC) WHERE channel = 'PHONE';
CREATE INDEX idx_orders_phone_caller_phone ON orders (caller_phone) WHERE channel = 'PHONE' AND caller_phone IS NOT NULL;
CREATE INDEX idx_orders_phone_caller_name ON orders (lower(caller_name) text_pattern_ops) WHERE channel = 'PHONE';
CREATE INDEX idx_orders_phone_slip ON orders (slip_reference) WHERE channel = 'PHONE' AND slip_reference IS NOT NULL;
