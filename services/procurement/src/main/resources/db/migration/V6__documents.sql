-- procurement V6: supplier and courier invoices -- evidence for a purchase order or a consignment.
--
-- An invoice is what makes a landed cost auditable a year later, and the only way to find out
-- whether the courier's bill contains a duty line at all (MVP_PLAN Phase 4). So a stored
-- document is EVIDENCE:
--
--   * it belongs to exactly one purchase order or consignment;
--   * it is never edited and never deleted by itself. A mistaken one is VOIDED, with a reason
--     and who and when, and stays on record. (The one exception is the cascade of deleting a
--     DRAFT consignment, which was never part of the books.)
--   * the same file is not stored twice for the same owner while it is in use.
--
-- The bytes are kept HERE, in the service's own database, not in object storage: the volume is
-- tiny (a handful of invoices a month, 5 MB at most each), and a document that lives in the
-- same transaction and the same backup as the thing it supports can never be left dangling,
-- orphaned or out of step with it. No extra service, bucket, credentials or failure mode.
--
-- A received consignment may still be given documents: the courier's invoice often arrives
-- after the goods. Documents are not part of the frozen record of costs.
CREATE TABLE procurement_document (
    id                UUID PRIMARY KEY,
    purchase_order_id UUID,
    consignment_id    UUID,
    kind              VARCHAR(20)  NOT NULL,
    -- As shown to a person: path stripped, no control characters or quotes, at most 120 long.
    filename          VARCHAR(120) NOT NULL,
    -- Decided from the file's own first bytes, never from what the uploader claimed.
    content_type      VARCHAR(50)  NOT NULL,
    size_bytes        INTEGER      NOT NULL,
    -- SHA-256 of the bytes, lower-case hex: the fingerprint that stops a double upload and lets
    -- anyone check a copy against what was stored.
    sha256            VARCHAR(64)  NOT NULL,
    content           BYTEA        NOT NULL,
    note              VARCHAR(200),
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    uploaded_by       VARCHAR(64),
    voided_at         TIMESTAMPTZ,
    voided_by         VARCHAR(64),
    void_reason       VARCHAR(200),
    CONSTRAINT fk_procurement_document_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    -- A draft consignment that is deleted takes its documents with it. The delete trigger below
    -- allows exactly that and nothing else.
    CONSTRAINT fk_procurement_document_consignment FOREIGN KEY (consignment_id) REFERENCES consignment (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_procurement_document_owner CHECK ((purchase_order_id IS NOT NULL)::int + (consignment_id IS NOT NULL)::int = 1),
    CONSTRAINT ck_procurement_document_kind CHECK (kind IN ('SUPPLIER_INVOICE', 'COURIER_INVOICE', 'ZRA_ASSESSMENT', 'OTHER')),
    CONSTRAINT ck_procurement_document_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    -- 5 MB at most, and the recorded size is the real size.
    CONSTRAINT ck_procurement_document_size CHECK (size_bytes BETWEEN 1 AND 5242880 AND octet_length(content) = size_bytes),
    CONSTRAINT ck_procurement_document_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- Voided means who, when AND why, all or none.
    CONSTRAINT ck_procurement_document_void CHECK (
        (voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL)
        OR (voided_at IS NOT NULL AND voided_by IS NOT NULL AND void_reason IS NOT NULL AND void_reason <> ''))
);

-- Once per owner while in use; a voided copy no longer counts, so a corrected scan of the same
-- file can be added again.
CREATE UNIQUE INDEX uq_procurement_document_consignment_sha ON procurement_document (consignment_id, sha256)
    WHERE consignment_id IS NOT NULL AND voided_at IS NULL;
CREATE UNIQUE INDEX uq_procurement_document_order_sha ON procurement_document (purchase_order_id, sha256)
    WHERE purchase_order_id IS NOT NULL AND voided_at IS NULL;

CREATE INDEX idx_procurement_document_consignment ON procurement_document (consignment_id, uploaded_at);
CREATE INDEX idx_procurement_document_order ON procurement_document (purchase_order_id, uploaded_at);

CREATE FUNCTION oriah_protect_procurement_document() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Allowed only when the owner is already gone: the cascade of deleting a draft consignment.
        IF (OLD.consignment_id IS NOT NULL AND EXISTS (SELECT 1 FROM consignment c WHERE c.id = OLD.consignment_id))
           OR (OLD.purchase_order_id IS NOT NULL AND EXISTS (SELECT 1 FROM purchase_order p WHERE p.id = OLD.purchase_order_id)) THEN
            RAISE EXCEPTION 'procurement documents are never deleted: void it instead, with a reason';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.voided_at IS NOT NULL THEN
        RAISE EXCEPTION 'procurement document is already voided and is never changed again';
    END IF;
    -- The only change a document ever takes is being voided, once.
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.purchase_order_id IS DISTINCT FROM OLD.purchase_order_id
       OR NEW.consignment_id IS DISTINCT FROM OLD.consignment_id
       OR NEW.kind IS DISTINCT FROM OLD.kind
       OR NEW.filename IS DISTINCT FROM OLD.filename
       OR NEW.content_type IS DISTINCT FROM OLD.content_type
       OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.sha256 IS DISTINCT FROM OLD.sha256
       OR NEW.content IS DISTINCT FROM OLD.content
       OR NEW.note IS DISTINCT FROM OLD.note
       OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
       OR NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by THEN
        RAISE EXCEPTION 'a procurement document is never changed: it can only be voided';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER procurement_document_protected
    BEFORE UPDATE OR DELETE ON procurement_document
    FOR EACH ROW EXECUTE FUNCTION oriah_protect_procurement_document();
