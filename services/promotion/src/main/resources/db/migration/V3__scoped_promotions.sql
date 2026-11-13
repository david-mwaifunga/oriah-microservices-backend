-- Phase 7 (slice 7.3): promotions as the client runs them (CLIENT_DECISIONS section 1.4, round 5: "they can be on a piece, a category or even sell
-- wide"). A promotion has a SCOPE (one product, one category with its sub-categories, or the whole shop), DATES (Lusaka calendar days, the
-- last day included) and a DISCOUNT PER PIECE: a Kwacha amount off each piece (FIXED) or a percentage (PERCENTAGE). It applies by itself to every
-- piece it covers during its dates; staff never choose a discount (Q12). The single-use discount CODE on a whole order is retired.
--
-- A promotion is never edited. An edit ENDS the old one (a stop, dated) and starts a new one that says which it replaces, so any past instant has
-- exactly one answer from the rows alone: a paper sale keyed after a promotion ended is still priced at its true date (Q30). The table refuses
-- UPDATE (except the one stop), DELETE and TRUNCATE.

DO $$
DECLARE
    old_rows INTEGER;
BEGIN
    SELECT count(*) INTO old_rows FROM promotions;
    IF old_rows > 0 THEN
        RAISE NOTICE 'V3: dropping % old discount code(s): a code on a whole order is not how the client runs a promotion (developer data only)', old_rows;
    END IF;
END
$$;

DROP TABLE promotions;

CREATE TABLE promotion (
    id            UUID PRIMARY KEY,
    name          VARCHAR(120)   NOT NULL,
    scope_type    VARCHAR(10)    NOT NULL,
    -- The product or category it covers (another service's id, so no foreign key); null for the whole shop.
    scope_id      UUID,
    discount_type VARCHAR(10)    NOT NULL,
    -- FIXED: Kwacha off each piece (never more than its price: the evaluator caps it). PERCENTAGE: percent of the piece's price.
    amount        NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    -- Lusaka calendar days: in force from 00:00 of the first day to the end of the last day.
    starts_on     DATE           NOT NULL,
    ends_on       DATE           NOT NULL,
    -- Set when the promotion is stopped (or replaced): in force strictly before this instant, never after.
    stopped_at    TIMESTAMPTZ,
    stopped_by    VARCHAR(128),
    -- The promotion this one replaces (an edit).
    replaces_id   UUID REFERENCES promotion (id),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by    VARCHAR(128)   NOT NULL,
    CONSTRAINT promotion_scope_known CHECK (scope_type IN ('PRODUCT', 'CATEGORY', 'SHOP')),
    CONSTRAINT promotion_scope_names_its_target CHECK ((scope_type = 'SHOP') = (scope_id IS NULL)),
    CONSTRAINT promotion_discount_type_known CHECK (discount_type IN ('FIXED', 'PERCENTAGE')),
    CONSTRAINT promotion_percentage_at_most_100 CHECK (discount_type = 'FIXED' OR amount <= 100),
    CONSTRAINT promotion_last_day_not_before_first CHECK (ends_on >= starts_on),
    CONSTRAINT promotion_stop_is_a_pair CHECK ((stopped_at IS NULL) = (stopped_by IS NULL))
);

CREATE INDEX promotion_by_dates ON promotion (starts_on, ends_on);
CREATE INDEX promotion_by_scope ON promotion (scope_type, scope_id);

CREATE OR REPLACE FUNCTION promotion_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'a promotion cannot be deleted: it is history';
    END IF;
    -- The one change allowed: a stop, written once.
    IF OLD.stopped_at IS NULL AND NEW.stopped_at IS NOT NULL AND NEW.stopped_by IS NOT NULL
       AND (NEW.id, NEW.name, NEW.scope_type, NEW.scope_id, NEW.discount_type, NEW.amount, NEW.starts_on, NEW.ends_on, NEW.replaces_id, NEW.created_at, NEW.created_by)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.name, OLD.scope_type, OLD.scope_id, OLD.discount_type, OLD.amount, OLD.starts_on, OLD.ends_on, OLD.replaces_id, OLD.created_at, OLD.created_by) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a promotion cannot be changed: stop it, or replace it with a new one';
END;
$$;

CREATE TRIGGER promotion_guard_row BEFORE UPDATE OR DELETE ON promotion FOR EACH ROW EXECUTE FUNCTION promotion_guard();
-- A row trigger never fires for TRUNCATE, so TRUNCATE needs its own.
CREATE TRIGGER promotion_guard_truncate BEFORE TRUNCATE ON promotion FOR EACH STATEMENT EXECUTE FUNCTION promotion_guard();
