-- Phase 7 (slice 7.2): shipping gets its own database. Delivery is priced PER TOWN and option, by admin, before the customer pays (CLIENT_DECISIONS
-- Q24, rounds 4 and 5), and a later change to a price never touches an order already placed (the order keeps its own amount).
--
--   province, town           Zambia's ten provinces and their main towns: reference data. A town is SWITCHED OFF, never deleted, because orders (in another
--                            database) point at it.
--   delivery_option          the ways a parcel can go (Oriah's own delivery, Yango, named couriers, bus-company parcel services): seeded, and admin may add
--                            more and switch them on and off. Self-collect is not here: it is always offered at no charge, outside the town matrix.
--   delivery_price           a price per town x option: a DRAFT until admin PUBLISHES it, then history: a new price replaces it and the old one is kept
--                            (SUPERSEDED). One live price per town and option. Prices are never seeded: they are the client's own figures.
--   delivery_settings        the free-delivery threshold (off until set), one row; every change to it is kept in delivery_settings_change.

CREATE OR REPLACE FUNCTION oriah_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TABLE province (
    id       UUID PRIMARY KEY,
    code     VARCHAR(16) NOT NULL UNIQUE,
    name     VARCHAR(60) NOT NULL UNIQUE,
    position INTEGER     NOT NULL
);

CREATE TABLE town (
    id          UUID PRIMARY KEY,
    province_id UUID         NOT NULL REFERENCES province (id),
    name        VARCHAR(80)  NOT NULL,
    -- Lower-case ASCII with hyphens: what a URL or a log line says. Unique, so no two rows are the same place.
    slug        VARCHAR(80)  NOT NULL UNIQUE,
    -- A town is switched off, never deleted: orders point at it.
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(128) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  VARCHAR(128),
    CONSTRAINT town_name_per_province UNIQUE (province_id, name)
);

CREATE INDEX town_province ON town (province_id, name);

CREATE TABLE delivery_option (
    code       VARCHAR(40)  PRIMARY KEY,
    name       VARCHAR(80)  NOT NULL UNIQUE,
    kind       VARCHAR(16)  NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    position   INTEGER      NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by VARCHAR(128),
    CONSTRAINT delivery_option_code_tidy CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT delivery_option_kind_known CHECK (kind IN ('OWN_DELIVERY', 'YANGO', 'COURIER', 'BUS_PARCEL'))
);

CREATE TABLE delivery_price (
    id                    UUID PRIMARY KEY,
    town_id               UUID          NOT NULL REFERENCES town (id),
    option_code           VARCHAR(40)   NOT NULL REFERENCES delivery_option (code),
    -- Oriah's charge when Oriah collects the delivery fee. Whole ngwee, in Kwacha only.
    amount                NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency              VARCHAR(3)    NOT NULL DEFAULT 'ZMW' CHECK (currency = 'ZMW'),
    -- Who may pay: Oriah collects the fee with the order, or the RECIPIENT pays the courier on arrival (then the order carries K0). At least one.
    allows_oriah_collects BOOLEAN       NOT NULL DEFAULT TRUE,
    allows_recipient_pays BOOLEAN       NOT NULL DEFAULT FALSE,
    eta_days_min          INTEGER,
    eta_days_max          INTEGER,
    status                VARCHAR(10)   NOT NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            VARCHAR(128)  NOT NULL,
    published_at          TIMESTAMPTZ,
    published_by          VARCHAR(128),
    superseded_at         TIMESTAMPTZ,
    CONSTRAINT delivery_price_status_known CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT delivery_price_someone_pays CHECK (allows_oriah_collects OR allows_recipient_pays),
    CONSTRAINT delivery_price_eta_together CHECK (
        (eta_days_min IS NULL AND eta_days_max IS NULL)
        OR (eta_days_min IS NOT NULL AND eta_days_max IS NOT NULL AND eta_days_min >= 0 AND eta_days_max >= eta_days_min)),
    CONSTRAINT delivery_price_published_has_time CHECK ((status = 'DRAFT') = (published_at IS NULL)),
    CONSTRAINT delivery_price_superseded_has_time CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL))
);

-- One live price per town and option, and one draft being prepared at a time.
CREATE UNIQUE INDEX delivery_price_one_published ON delivery_price (town_id, option_code) WHERE status = 'PUBLISHED';
CREATE UNIQUE INDEX delivery_price_one_draft ON delivery_price (town_id, option_code) WHERE status = 'DRAFT';
CREATE INDEX delivery_price_by_town ON delivery_price (town_id, option_code, status);

-- A published price is history: what a customer was quoted is what was published. A draft may be edited, published or deleted; a published
-- price may only become SUPERSEDED (nothing else about it changes), and a superseded one never changes again.
CREATE OR REPLACE FUNCTION delivery_price_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'a published price cannot be deleted: it is history (price %)', OLD.id;
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status = 'DRAFT' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status = 'SUPERSEDED'
       AND NEW.town_id = OLD.town_id AND NEW.option_code = OLD.option_code
       AND NEW.amount = OLD.amount AND NEW.currency = OLD.currency
       AND NEW.allows_oriah_collects = OLD.allows_oriah_collects AND NEW.allows_recipient_pays = OLD.allows_recipient_pays
       AND NEW.eta_days_min IS NOT DISTINCT FROM OLD.eta_days_min AND NEW.eta_days_max IS NOT DISTINCT FROM OLD.eta_days_max
       AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at AND NEW.published_by IS NOT DISTINCT FROM OLD.published_by THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a published price cannot be changed: supersede it with a new price (price %)', OLD.id;
END;
$$;

CREATE TRIGGER delivery_price_guard_update BEFORE UPDATE ON delivery_price FOR EACH ROW EXECUTE FUNCTION delivery_price_guard();
CREATE TRIGGER delivery_price_guard_delete BEFORE DELETE ON delivery_price FOR EACH ROW EXECUTE FUNCTION delivery_price_guard();

-- The free-delivery threshold: off (NULL) until admin sets it; compared with the goods total AFTER discount. One row.
CREATE TABLE delivery_settings (
    id                      SMALLINT PRIMARY KEY,
    free_delivery_threshold NUMERIC(12, 2),
    version                 BIGINT       NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by              VARCHAR(128) NOT NULL,
    CONSTRAINT delivery_settings_one_row CHECK (id = 1),
    CONSTRAINT delivery_settings_threshold_positive CHECK (free_delivery_threshold IS NULL OR free_delivery_threshold > 0)
);

INSERT INTO delivery_settings (id, free_delivery_threshold, updated_by) VALUES (1, NULL, 'system:seed');

-- Every change to the threshold, kept for ever.
CREATE TABLE delivery_settings_change (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    threshold_before NUMERIC(12, 2),
    threshold_after  NUMERIC(12, 2),
    changed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by       VARCHAR(128) NOT NULL,
    reason           VARCHAR(200)
);

CREATE TRIGGER delivery_settings_change_append_only_row
    BEFORE UPDATE OR DELETE ON delivery_settings_change
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();

-- A row trigger never fires for TRUNCATE, so TRUNCATE needs its own.
CREATE TRIGGER delivery_settings_change_append_only_truncate
    BEFORE TRUNCATE ON delivery_settings_change
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
