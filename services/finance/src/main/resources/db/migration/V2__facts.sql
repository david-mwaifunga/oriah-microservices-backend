-- Phase 10 (finance): the facts the ledger is derived from, the anomalies that say when something does not add up, the stored snapshot of a reported month,
-- and the provider statements the bookkeeper types in.
--
-- A FACT is what another service said, kept exactly (insert-only), so a figure that depends on several events (a confirmation, its sale, a void) is always worked
-- out from everything finance has seen, whichever event arrived first. Nothing personal is stored: no caller, recipient, address, phone, reference, free text or
-- reason from any event.

-- A sale, from order.paid: one row per confirmation (the idempotency key of a sale) and one per line.
CREATE TABLE sale (
    confirmation_id          UUID         PRIMARY KEY,
    order_id                 UUID         NOT NULL UNIQUE,
    schema_version           INTEGER      NOT NULL,
    -- The day the money ARRIVED: the true date of the sale. A phone sale keyed late carries its own date in sale_occurred_at, which is never the one counted.
    arrived_at               TIMESTAMPTZ  NOT NULL,
    sale_occurred_at         TIMESTAMPTZ,
    source_recorded_at       TIMESTAMPTZ,
    channel                  VARCHAR(10),
    payment_method           VARCHAR(20),
    gross                    NUMERIC(12, 2) NOT NULL,
    fee                      NUMERIC(12, 2) NOT NULL,
    net                      NUMERIC(12, 2) NOT NULL,
    shipping                 NUMERIC(12, 2) NOT NULL,
    discount_total           NUMERIC(12, 2) NOT NULL,
    waived_amount            NUMERIC(12, 2),
    delivery_payer           VARCHAR(20),
    delivery_option          VARCHAR(40),
    delivery_town_id         UUID,
    delivery_price_source    VARCHAR(10),
    agreed_amount            NUMERIC(12, 2),
    quoted_amount            NUMERIC(12, 2),
    receipt_number           VARCHAR(40),
    -- The original order.paid was never seen; this sale was recovered from a republished copy.
    recovered_from_republish BOOLEAN      NOT NULL DEFAULT FALSE,
    source_event_id          UUID,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sale_line (
    sale_line_id       UUID         PRIMARY KEY,
    confirmation_id    UUID         NOT NULL REFERENCES sale (confirmation_id),
    variant_id         UUID         NOT NULL,
    product_id         UUID,
    quantity           INTEGER      NOT NULL CHECK (quantity > 0),
    list_price         NUMERIC(12, 2) NOT NULL,
    discount_per_piece NUMERIC(12, 2) NOT NULL,
    promotion_id       UUID,
    title              VARCHAR(255)
);

CREATE INDEX ix_sale_line_confirmation ON sale_line (confirmation_id);

-- What payment said about a confirmation: it came in (CONFIRMED), it was taken back (VOIDED), its money could not pay a sale and is owed back (HELD), and the sale it paid
-- (SALE, written with the sale). The fee posts once, from CONFIRMED; the rest is derived from which of these exist.
CREATE TABLE confirmation_fact (
    confirmation_id  UUID         NOT NULL,
    kind             VARCHAR(10)  NOT NULL CHECK (kind IN ('CONFIRMED', 'VOIDED', 'HELD', 'SALE')),
    order_id         UUID,
    method           VARCHAR(20),
    gross            NUMERIC(12, 2),
    fee              NUMERIC(12, 2),
    arrived_at       TIMESTAMPTZ,
    voided_at        TIMESTAMPTZ,
    outcome          VARCHAR(10),
    held_reason      VARCHAR(30),
    owed_gross       NUMERIC(12, 2),
    source_event_id  UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (confirmation_id, kind)
);

-- A parcel left: when, and what the courier was paid (null: none due). Written for every dispatch, with or without a fare, so a fare typed in by hand can only follow it.
CREATE TABLE dispatch_fact (
    order_id        UUID         PRIMARY KEY,
    dispatched_at   TIMESTAMPTZ  NOT NULL,
    fare_paid       NUMERIC(12, 2),
    payer           VARCHAR(20),
    source_event_id UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- An order finance has heard of (order.created, order.paid): what a typed expense that names an order is checked against.
CREATE TABLE order_ref (
    order_id      UUID        PRIMARY KEY,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    channel       VARCHAR(10)
);

-- A name for the report. Display only, never money: the one table that changes, and rebuildable from catalog's events.
CREATE TABLE product_name (
    product_id UUID         PRIMARY KEY,
    title      VARCHAR(255) NOT NULL,
    kind       VARCHAR(12),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_seq   BIGINT       NOT NULL DEFAULT 0
);

-- A consignment line as procurement last announced it (every revision kept): what the disclosure of estimated opening stock lists.
CREATE TABLE consignment_line_fact (
    consignment_line_id   UUID         NOT NULL,
    revision              INTEGER      NOT NULL CHECK (revision >= 1),
    consignment_id        UUID         NOT NULL,
    consignment_reference VARCHAR(20),
    variant_id            UUID         NOT NULL,
    sku                   VARCHAR(60),
    kind                  VARCHAR(12),
    quantity              INTEGER      NOT NULL,
    landed_total          NUMERIC(12, 2) NOT NULL,
    is_estimated          BOOLEAN      NOT NULL,
    supplier_known        BOOLEAN      NOT NULL,
    received_at           TIMESTAMPTZ  NOT NULL,
    source_recorded_at    TIMESTAMPTZ,
    PRIMARY KEY (consignment_line_id, revision)
);

-- Things that do not add up, or that finance could not apply. A log, not a state: an anomaly is OPEN while the latest row for its (code, subject) is a RAISE, so
-- the same thing can be closed and happen again. Detail holds ids, amounts and codes only, never a value a person typed.
CREATE TABLE anomaly_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(40)  NOT NULL,
    subject         VARCHAR(80)  NOT NULL,
    action          VARCHAR(12)  NOT NULL CHECK (action IN ('RAISE', 'ACK', 'AUTO_RESOLVE')),
    severity        VARCHAR(5)   NOT NULL CHECK (severity IN ('INFO', 'WARN', 'ERROR')),
    detail          JSONB,
    by_actor        VARCHAR(128),
    at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    note            VARCHAR(200),
    source_event_id UUID,
    CONSTRAINT ck_anomaly_ack_note CHECK (action <> 'ACK' OR (note IS NOT NULL AND char_length(btrim(note)) >= 5 AND by_actor IS NOT NULL))
);

CREATE INDEX ix_anomaly_subject ON anomaly_log (code, subject, id);

-- A month reported: the whole computed month, stored (lines, memo, products with their names, disclosure), as of the moment it was issued. This is the evidence of
-- what was reported; whatever is entered or learned after it shows as a change since. More than one per month is allowed (a note says why).
CREATE TABLE report_issue (
    issue_id           UUID         PRIMARY KEY,
    month              DATE         NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    issue_no           INTEGER      NOT NULL CHECK (issue_no >= 1),
    as_of              TIMESTAMPTZ  NOT NULL,
    issued_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    issued_by          VARCHAR(128) NOT NULL,
    note               VARCHAR(200),
    incomplete_reasons JSONB,
    snapshot           JSONB        NOT NULL,
    UNIQUE (month, issue_no)
);

-- What a provider's own statement says it charged for a month (the fee total per method), typed by the bookkeeper to reconcile against what the system posted.
-- The latest row per (month, method) counts; none is ever changed.
CREATE TABLE statement_check (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    month          DATE         NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    method         VARCHAR(20)  NOT NULL,
    statement_fees NUMERIC(12, 2) NOT NULL CHECK (statement_fees >= 0),
    note           VARCHAR(200),
    entered_by     VARCHAR(128) NOT NULL,
    entered_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- An event finance read and deliberately did nothing with, and why (for example an order.paid from before confirmations had ids).
CREATE TABLE skipped_fact (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   UUID         NOT NULL UNIQUE,
    producer   VARCHAR(30),
    event_type VARCHAR(60)  NOT NULL,
    reason     VARCHAR(60)  NOT NULL,
    seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Insert-only: a fact is never edited or removed, so a figure can always be explained from what was seen.
CREATE TRIGGER sale_append_only_row BEFORE UPDATE OR DELETE ON sale FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_append_only_truncate BEFORE TRUNCATE ON sale FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_line_append_only_row BEFORE UPDATE OR DELETE ON sale_line FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_line_append_only_truncate BEFORE TRUNCATE ON sale_line FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER confirmation_fact_append_only_row BEFORE UPDATE OR DELETE ON confirmation_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER confirmation_fact_append_only_truncate BEFORE TRUNCATE ON confirmation_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER dispatch_fact_append_only_row BEFORE UPDATE OR DELETE ON dispatch_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER dispatch_fact_append_only_truncate BEFORE TRUNCATE ON dispatch_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER consignment_line_fact_append_only_row BEFORE UPDATE OR DELETE ON consignment_line_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER consignment_line_fact_append_only_truncate BEFORE TRUNCATE ON consignment_line_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER anomaly_log_append_only_row BEFORE UPDATE OR DELETE ON anomaly_log FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER anomaly_log_append_only_truncate BEFORE TRUNCATE ON anomaly_log FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER report_issue_append_only_row BEFORE UPDATE OR DELETE ON report_issue FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER report_issue_append_only_truncate BEFORE TRUNCATE ON report_issue FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER statement_check_append_only_row BEFORE UPDATE OR DELETE ON statement_check FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER statement_check_append_only_truncate BEFORE TRUNCATE ON statement_check FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER skipped_fact_append_only_row BEFORE UPDATE OR DELETE ON skipped_fact FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER skipped_fact_append_only_truncate BEFORE TRUNCATE ON skipped_fact FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
