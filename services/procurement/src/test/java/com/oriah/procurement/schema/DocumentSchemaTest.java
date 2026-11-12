package com.oriah.procurement.schema;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Supplier and courier invoices as the database itself keeps them, with raw SQL and no entity.
 *
 * <p>An invoice is what makes a landed cost auditable a year later, and the only way to find out
 * whether the courier's bill contains a duty line at all. So a stored document is EVIDENCE: it
 * belongs to exactly one purchase order or consignment, it is never edited and never deleted (a
 * mistaken one is VOIDED, with a reason, and stays on record), and the same file is not stored
 * twice for the same owner. The bytes live in the service's own database, so a document and the
 * thing it supports can never drift apart in a backup.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class DocumentSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID consignment;
    private UUID order;

    @BeforeEach
    void anOwner() {
        ProcurementTestData.wipe(jdbc);
        UUID supplier = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, name, country) VALUES (?, 'Document Schema Supplier', 'AE')", supplier);
        order = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_order (id, supplier_id, placed_on, status) VALUES (?, ?, current_date, 'OPEN')",
                order, supplier);
        consignment = UUID.randomUUID();
        jdbc.update("INSERT INTO consignment (id, status, is_opening, is_estimated) VALUES (?, 'DRAFT', false, false)",
                consignment);
    }

    // ------------------------------------------------------------ fixtures

    private static final byte[] PDF = "%PDF-1.4 a tiny invoice".getBytes();

    private UUID documentOf(UUID consignmentId, UUID orderId, String kind, byte[] content, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO procurement_document (id, purchase_order_id, consignment_id, kind, filename, content_type,
                                                  size_bytes, sha256, content, uploaded_by)
                VALUES (?, ?, ?, ?, 'invoice.pdf', 'application/pdf', ?, ?, ?, 'kc-maya')""",
                id, orderId, consignmentId, kind, content.length, sha, content);
        return id;
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    private UUID consignmentDocument(String sha) {
        return documentOf(consignment, null, "COURIER_INVOICE", PDF, sha);
    }

    // -------------------------------------------------------------- owners

    @Test
    @DisplayName("a document belongs to exactly one owner: a purchase order or a consignment, never both and never neither")
    void exactlyOneOwner() {
        documentOf(consignment, null, "COURIER_INVOICE", PDF, sha('a'));
        documentOf(null, order, "SUPPLIER_INVOICE", PDF, sha('b'));

        assertThatThrownBy(() -> documentOf(consignment, order, "SUPPLIER_INVOICE", PDF, sha('c')))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_owner");
        assertThatThrownBy(() -> documentOf(null, null, "SUPPLIER_INVOICE", PDF, sha('d')))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_owner");
    }

    @Test
    @DisplayName("the owner must exist")
    void theOwnerExists() {
        assertThatThrownBy(() -> documentOf(UUID.randomUUID(), null, "COURIER_INVOICE", PDF, sha('a')))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_procurement_document_consignment");
        assertThatThrownBy(() -> documentOf(null, UUID.randomUUID(), "SUPPLIER_INVOICE", PDF, sha('a')))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_procurement_document_order");
    }

    // ------------------------------------------------------------ the file

    @Test
    @DisplayName("a document is a supplier invoice, a courier invoice, a ZRA assessment or something else, and a PDF, JPEG or PNG")
    void kindsAndTypesAreClosed() {
        for (String kind : new String[] {"SUPPLIER_INVOICE", "COURIER_INVOICE", "ZRA_ASSESSMENT", "OTHER"}) {
            documentOf(consignment, null, kind, PDF, kindSha(kind));
        }
        assertThatThrownBy(() -> documentOf(consignment, null, "RECEIPT", PDF, sha('1')))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_kind");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO procurement_document (id, consignment_id, kind, filename, content_type, size_bytes, sha256, content)
                VALUES (?, ?, 'OTHER', 'x.exe', 'application/x-msdownload', 1, ?, ?)""",
                UUID.randomUUID(), consignment, sha('2'), new byte[] {1}))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_type");
    }

    private static String kindSha(String kind) {
        return switch (kind) {
            case "SUPPLIER_INVOICE" -> sha('a');
            case "COURIER_INVOICE" -> sha('b');
            case "ZRA_ASSESSMENT" -> sha('c');
            default -> sha('d');
        };
    }

    @Test
    @DisplayName("the recorded size is the real size, between 1 byte and 5 MB, and the fingerprint is 64 hex digits")
    void sizeAndFingerprint() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO procurement_document (id, consignment_id, kind, filename, content_type, size_bytes, sha256, content)
                VALUES (?, ?, 'OTHER', 'x.pdf', 'application/pdf', 999, ?, ?)""",
                UUID.randomUUID(), consignment, sha('a'), PDF))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_size");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO procurement_document (id, consignment_id, kind, filename, content_type, size_bytes, sha256, content)
                VALUES (?, ?, 'OTHER', 'x.pdf', 'application/pdf', 0, ?, ?)""",
                UUID.randomUUID(), consignment, sha('a'), new byte[0]))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_size");
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> documentOf(consignment, null, "OTHER", tooBig, sha('a')))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_size");
        byte[] exactly5Mb = new byte[5 * 1024 * 1024];
        documentOf(consignment, null, "OTHER", exactly5Mb, sha('e'));

        assertThatThrownBy(() -> documentOf(consignment, null, "OTHER", PDF, "NOT-A-FINGERPRINT"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_sha256");
        assertThatThrownBy(() -> documentOf(consignment, null, "OTHER", PDF, "A".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_sha256");
    }

    // ------------------------------------------------------------- dedup

    @Test
    @DisplayName("the same file is not kept twice for the same owner while it is in use, but may be again once the first is voided")
    void noDuplicatesWhileActive() {
        UUID first = consignmentDocument(sha('a'));

        assertThatThrownBy(() -> consignmentDocument(sha('a')))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_procurement_document_consignment_sha");
        jdbc.update("UPDATE procurement_document SET voided_at = now(), voided_by = 'kc-maya', void_reason = 'wrong one' WHERE id = ?", first);
        consignmentDocument(sha('a'));
    }

    @Test
    @DisplayName("the same file may belong to different owners, and to a consignment and a purchase order")
    void sameFileDifferentOwners() {
        consignmentDocument(sha('a'));
        documentOf(null, order, "SUPPLIER_INVOICE", PDF, sha('a'));
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO consignment (id, status, is_opening, is_estimated) VALUES (?, 'DRAFT', false, false)", other);
        documentOf(other, null, "COURIER_INVOICE", PDF, sha('a'));

        assertThatThrownBy(() -> documentOf(null, order, "SUPPLIER_INVOICE", PDF, sha('a')))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_procurement_document_order_sha");
    }

    // -------------------------------------------------------- evidence rules

    @Test
    @DisplayName("a document is never changed: not its file, its name, its kind, its owner or who uploaded it")
    void documentsAreImmutable() {
        UUID id = consignmentDocument(sha('a'));

        for (String change : new String[] {"content = '\\x25504446'::bytea", "filename = 'other.pdf'",
                "kind = 'OTHER'", "sha256 = '" + sha('b') + "'", "note = 'edited'", "uploaded_by = 'someone else'",
                "size_bytes = 3"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE procurement_document SET " + change + " WHERE id = ?", id))
                    .as(change)
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE procurement_document SET consignment_id = NULL, purchase_order_id = ? WHERE id = ?",
                order, id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a document is never deleted by itself: a mistaken one is voided, with a reason, and stays on record")
    void documentsAreNeverDeleted() {
        UUID id = consignmentDocument(sha('a'));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM procurement_document WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement_document", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("voiding records who, when and why, needs a reason, and can happen only once")
    void voiding() {
        UUID id = consignmentDocument(sha('a'));

        assertThatThrownBy(() -> jdbc.update("UPDATE procurement_document SET voided_at = now(), voided_by = 'kc-maya' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_void");
        assertThatThrownBy(() -> jdbc.update("UPDATE procurement_document SET void_reason = 'because' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_procurement_document_void");
        jdbc.update("UPDATE procurement_document SET voided_at = now(), voided_by = 'kc-maya', void_reason = 'wrong file' WHERE id = ?", id);

        assertThatThrownBy(() -> jdbc.update("UPDATE procurement_document SET void_reason = 'changed my mind' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE procurement_document SET voided_at = NULL, voided_by = NULL, void_reason = NULL WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT void_reason FROM procurement_document WHERE id = ?", String.class, id))
                .isEqualTo("wrong file");
    }

    @Test
    @DisplayName("a draft consignment that is deleted takes its documents with it; nothing else can delete one")
    void aDeletedDraftTakesItsDocuments() {
        consignmentDocument(sha('a'));
        UUID kept = documentOf(null, order, "SUPPLIER_INVOICE", PDF, sha('b'));

        jdbc.update("DELETE FROM consignment WHERE id = ?", consignment);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement_document WHERE consignment_id = ?", Long.class, consignment))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement_document WHERE id = ?", Long.class, kept))
                .as("a purchase order's documents are untouched").isEqualTo(1);
    }

    @Test
    @DisplayName("a received consignment keeps its documents, and can still be given more: an invoice often arrives after the goods")
    void receivedConsignmentsKeepAndTakeDocuments() {
        UUID id = consignmentDocument(sha('a'));
        // The line and cost freeze triggers say nothing about documents.
        jdbc.update("UPDATE consignment SET status = 'RECEIVED', received_at = now() WHERE id = ?", consignment);

        documentOf(consignment, null, "COURIER_INVOICE", PDF, sha('b'));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement_document WHERE consignment_id = ?", Long.class, consignment))
                .isEqualTo(2);
        jdbc.update("UPDATE procurement_document SET voided_at = now(), voided_by = 'kc-maya', void_reason = 'duplicate scan' WHERE id = ?", id);
    }
}
