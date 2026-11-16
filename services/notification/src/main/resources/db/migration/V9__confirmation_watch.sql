-- One row per staff-confirmed payment notification has heard of, from either topic, in any order.
--
-- "Is this payment's sale stuck?" needs facts from two topics: payment says it recorded the money and
-- keeps announcing it (oriah.payment); order says what became of it (oriah.order: the sale was paid, or
-- the money could not pay it). The two travel separately and can arrive in either order, so both
-- listeners LOCK this row before they decide anything, and neither ever decides on a stale picture.
--
-- Every time here is the time the FACT was recorded by its producer (the envelope's recorded-at), never
-- notification's own clock, so history read late looks as old as it is. Ids, an amount, a method, a
-- reason code and times: no payment reference, no person, no free text.
CREATE TABLE confirmation_watch (
    confirmation_id   UUID           PRIMARY KEY,
    order_id          UUID           NOT NULL,
    method            VARCHAR(20),
    gross             NUMERIC(19, 2),
    currency          VARCHAR(3),
    -- The highest announcement number seen: payment announces a confirmation again until order answers.
    highest_attempt   INTEGER        NOT NULL DEFAULT 0,
    first_seen_at     TIMESTAMPTZ    NOT NULL,
    -- The three possible answers. Any one of them means the payment is no longer waiting.
    paid_at           TIMESTAMPTZ,
    unsettled_at      TIMESTAMPTZ,
    unsettled_reason  VARCHAR(30),
    voided_at         TIMESTAMPTZ,
    CONSTRAINT confirmation_watch_money_whole CHECK ((gross IS NULL) = (currency IS NULL)),
    CONSTRAINT confirmation_watch_attempt_not_negative CHECK (highest_attempt >= 0),
    CONSTRAINT confirmation_watch_unsettled_whole CHECK ((unsettled_at IS NULL) = (unsettled_reason IS NULL))
);
CREATE INDEX idx_confirmation_watch_order ON confirmation_watch (order_id);
