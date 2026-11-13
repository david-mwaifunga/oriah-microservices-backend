-- Phase 7 (slice 7.1): reservations with an owner and a clock, boxes held by orders, the box swap, and inventory as a producer of events.
--
-- Until now a reservation was a bare integer on the stock row (inventory.reserved): no owner, no time, nothing to say whose unit it was or
-- when it should be let go, so "release exactly once" could not be built on it. From here a reservation is a header for ONE order with a
-- line per piece and per box; units are held by it until it is consumed (the sale), released (cancel) or expires (the sweeper).
--
-- inventory.reserved stays the sum of the HELD lines' quantities per variant (checked after every change, and by /reconciliation).
-- A box line is taken from the product's set-aside first (box_commitment) and from free boxes after: the set-aside is a PROMISE that stays
-- visible while an order holds part of it, so box_commitment.quantity is the whole promise and box_commitment.held the part currently
-- converted into reservations; inventory.committed = sum(quantity - held). The line records where its units came from, so a release puts
-- them back where they came from, once.
--
-- No foreign key from the new tables to inventory, stock_action, cost_lot or box_commitment: a promise may be let go (its record deleted)
-- while an order still holds units that came from it, and the action ids on a sale are the caller's ids, global by design.

-- 0. The outbox: inventory publishes facts now (reservation.expired, reservation.consumed, box.swapped) on oriah.inventory.
--    Sections copied verbatim from shared/oriah-events' reference SQL (src/test/resources/db/oriah-events/V1__outbox.sql); the module's
--    OutboxSchemaGuard proves the copy is exact. processed_event, processed_aggregate and outbox_catch_up already exist (V6, V9).

-- Reference SQL: the outbox and its relay cursor.
--
-- A producing service copies this file's statements into its own next-free
-- Flyway migration (the module ships no migration of its own, so each
-- service's version numbers stay its own). OutboxSchemaGuard, in this
-- module's test-jar, proves the copy is exact.
--
-- The outbox is the service's permanent log of what happened. Each row is
-- written in the same transaction as the change it describes, so the log
-- can never claim a change that rolled back, nor miss one that committed.

-- Refuses any change to a row of an append-only table. Shared by every
-- append-only table (CREATE OR REPLACE, so copying several reference files
-- into one migration is harmless). The message names the table and the
-- operation, so a refused statement says exactly what was refused.
CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TABLE outbox (
    -- The relay's position. Appends are serialised by an advisory lock, so
    -- ids are handed out in commit order and the relay can never skip a row
    -- that commits late. Keep the sequence's defaults (START 1, CACHE 1):
    -- the relay's cursor starts at 0 and sends only ids above it, and a
    -- larger cache lets each connection hold back a block of ids, so ids no
    -- longer follow commit order (OutboxSchemaGuard checks both).
    outbox_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The event's identity everywhere downstream; consumers dedupe on it.
    event_id       UUID NOT NULL UNIQUE,
    -- Which thing the event is about, e.g. ('order', '<order id>').
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    -- 1, 2, 3 ... per aggregate: lets a projection skip an older snapshot.
    seq            BIGINT NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    schema_version INT NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    -- When it happened in the real world. Never defaulted: a fact keyed in
    -- late carries its true, earlier date.
    occurred_at    TIMESTAMPTZ NOT NULL,
    -- When this row was written.
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Who did it: 'user:<keycloak subject>' or 'system:<name>'.
    actor          VARCHAR(128) NOT NULL,
    -- json, not jsonb: json keeps the text exactly as written, so the bytes
    -- stored are the bytes published.
    payload        JSON NOT NULL,
    UNIQUE (aggregate_type, aggregate_id, seq)
);

CREATE TRIGGER outbox_append_only_row
    BEFORE UPDATE OR DELETE ON outbox
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

-- A row trigger never fires for TRUNCATE, so TRUNCATE needs its own.
CREATE TRIGGER outbox_append_only_truncate
    BEFORE TRUNCATE ON outbox
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();

-- How far the relay has published. Bookkeeping, not a fact, so it changes.
CREATE TABLE outbox_relay_cursor (
    relay          VARCHAR(64) PRIMARY KEY,
    last_outbox_id BIGINT NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Seeded here, in the same migration: the relay locks this row to take its
-- turn, so without it the relay would never publish anything.
INSERT INTO outbox_relay_cursor (relay, last_outbox_id, updated_at)
VALUES ('main', 0, now());


-- 1. Ownerless counters. A reserved count with no owner can never be released by anyone, and once reserved is checked against the lines
--    it would read as a mismatch for ever. Developer data only (nothing goes live before every phase is built); V7 did the same for stock
--    that had no cost.
DO $$
DECLARE
    cleared INTEGER;
BEGIN
    SELECT count(*) INTO cleared FROM inventory WHERE reserved <> 0;
    IF cleared > 0 THEN
        RAISE NOTICE 'V11: clearing the ownerless reserved count of % stock row(s): a reservation now has an owner and a clock', cleared;
        UPDATE inventory SET reserved = 0 WHERE reserved <> 0;
    END IF;
END
$$;

-- 2. The part of a product's set-aside that orders are holding right now. quantity stays the whole promise (and stays above zero: a promise
--    that is used up or let go is deleted, as before).
ALTER TABLE box_commitment ADD COLUMN held INTEGER NOT NULL DEFAULT 0;
ALTER TABLE box_commitment ADD CONSTRAINT box_commitment_held_within_quantity CHECK (held >= 0 AND held <= quantity);

-- 3. A reservation: one order's hold on its pieces and boxes.
CREATE TABLE reservation (
    id         UUID PRIMARY KEY,
    -- The order it is for. Not a foreign key: order lives in another service, and the caller mints this id.
    order_id   UUID         NOT NULL,
    status     VARCHAR(10)  NOT NULL,
    held_at    TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    -- When it was consumed, released or expired. Null exactly while it is HELD.
    ended_at   TIMESTAMPTZ,
    created_by VARCHAR(128) NOT NULL,
    CONSTRAINT reservation_status_known CHECK (status IN ('HELD', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT reservation_expiry_after_hold CHECK (expires_at > held_at),
    CONSTRAINT reservation_ended_when_not_held CHECK ((status = 'HELD') = (ended_at IS NULL))
);

-- An order holds at most one reservation at a time (a second confirm with a new key must not hold the same piece twice); ended ones sit beside it.
CREATE UNIQUE INDEX reservation_one_held_per_order ON reservation (order_id) WHERE status = 'HELD';
-- What the sweeper looks for.
CREATE INDEX reservation_held_expiry ON reservation (expires_at, id) WHERE status = 'HELD';
CREATE INDEX reservation_order ON reservation (order_id, held_at);

-- 4. A line: a piece or a box, how many, and where the units came from.
CREATE TABLE reservation_line (
    id             UUID PRIMARY KEY,
    reservation_id UUID        NOT NULL REFERENCES reservation (id),
    -- The caller's id for this sale line: it becomes the stock action's id when the reservation is consumed. The same sale line may be held
    -- again by a LATER reservation of the same order (the first having ended), but a stock action id is used once for ever.
    sale_line_id   UUID        NOT NULL,
    variant_id     UUID        NOT NULL,
    kind           VARCHAR(5)  NOT NULL,
    -- For a box line, the product the box is for (its set-aside is that product's record).
    product_id     UUID,
    quantity       INTEGER     NOT NULL,
    from_committed INTEGER     NOT NULL DEFAULT 0,
    from_free      INTEGER     NOT NULL,
    -- Which promise the committed units came from (a plain id, no foreign key: the promise may be let go while this is held).
    commitment_id  UUID,
    CONSTRAINT reservation_line_kind_known CHECK (kind IN ('GOODS', 'BOX')),
    CONSTRAINT reservation_line_quantity_positive CHECK (quantity > 0),
    CONSTRAINT reservation_line_split_adds_up CHECK (from_committed >= 0 AND from_free >= 0 AND from_committed + from_free = quantity),
    CONSTRAINT reservation_line_box_names_product CHECK ((kind = 'BOX') = (product_id IS NOT NULL)),
    CONSTRAINT reservation_line_goods_not_committed CHECK (kind = 'BOX' OR from_committed = 0),
    CONSTRAINT reservation_line_commitment_only_with_committed CHECK ((commitment_id IS NOT NULL) = (from_committed > 0)),
    UNIQUE (reservation_id, sale_line_id)
);

CREATE INDEX reservation_line_variant ON reservation_line (variant_id);

-- 5. A box swap after payment, kept as a fact of its own: the original box's sale was reversed and the replacement's sold, both dated to the
--    sale (occurred_at) and recorded now. Keyed by the amendment id order mints, so a retry changes nothing twice.
CREATE TABLE box_swap (
    amendment_id          UUID PRIMARY KEY,
    order_id              UUID         NOT NULL,
    product_id            UUID         NOT NULL,
    original_action_id    UUID         NOT NULL,
    replacement_action_id UUID         NOT NULL,
    quantity              INTEGER      NOT NULL,
    reason                VARCHAR(200) NOT NULL,
    occurred_at           TIMESTAMPTZ  NOT NULL,
    recorded_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor                 VARCHAR(128) NOT NULL,
    CONSTRAINT box_swap_quantity_positive CHECK (quantity > 0),
    CONSTRAINT box_swap_different_actions CHECK (original_action_id <> replacement_action_id)
);

CREATE TRIGGER box_swap_append_only_row
    BEFORE UPDATE OR DELETE ON box_swap
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

-- A row trigger never fires for TRUNCATE, so TRUNCATE needs its own.
CREATE TRIGGER box_swap_append_only_truncate
    BEFORE TRUNCATE ON box_swap
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
