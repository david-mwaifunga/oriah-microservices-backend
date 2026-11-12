package com.oriah.procurement.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.procurement.dto.DocumentResponse;
import com.oriah.procurement.model.DocumentKind;
import com.oriah.procurement.model.DocumentType;

import java.time.Clock;

/**
 * Supplier and courier invoices, kept as evidence against a purchase order or a consignment.
 *
 * <h2>What is kept, and how</h2>
 *
 * The bytes live in this service's own database (V6), in the same transaction and the same backup as
 * the thing they support. The file is judged by its own first bytes (a PDF, JPEG or PNG; whatever the
 * uploader called it is ignored), fingerprinted with SHA-256, and stored once per owner while in use.
 * Five megabytes is the most. It is never edited or deleted (the database refuses both): a mistaken
 * one is voided, with a reason, and stays on record and downloadable.
 *
 * <h2>Plain SQL, not entities</h2>
 *
 * A listing must never load the files, and an entity would drag a 5 MB column into every read. The
 * list asks for the details only; the bytes are read by one statement, when someone downloads.
 *
 * <p>The owner's row is read FOR SHARE when a document is added, so a draft consignment cannot be
 * deleted (which takes its documents with it) while one is being attached to it.
 */
@Service
@Transactional
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ACTIVE_PER_OWNER = 10;
    static final int MAX_FILENAME = 120;
    static final int MAX_NOTE = 200;

    /** Who a document belongs to. */
    public enum Owner {
        PURCHASE_ORDER("purchase_order_id", "purchase_order", "PurchaseOrder", DocumentResponse.PURCHASE_ORDER),
        CONSIGNMENT("consignment_id", "consignment", "Consignment", DocumentResponse.CONSIGNMENT);

        private final String column;
        private final String table;
        private final String resource;
        private final String label;

        Owner(String column, String table, String resource, String label) {
            this.column = column;
            this.table = table;
            this.resource = resource;
            this.label = label;
        }
    }

    private static final String COLUMNS = "id, purchase_order_id, consignment_id, kind, filename, content_type, "
            + "size_bytes, sha256, note, uploaded_at, uploaded_by, voided_at, voided_by, void_reason";

    private static final RowMapper<DocumentResponse> DOCUMENT = (rs, n) -> {
        UUID order = (UUID) rs.getObject("purchase_order_id");
        UUID consignment = (UUID) rs.getObject("consignment_id");
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new DocumentResponse((UUID) rs.getObject("id"),
                order != null ? DocumentResponse.PURCHASE_ORDER : DocumentResponse.CONSIGNMENT,
                order != null ? order : consignment, rs.getString("kind"), rs.getString("filename"),
                rs.getString("content_type"), rs.getInt("size_bytes"), rs.getString("sha256"), rs.getString("note"),
                rs.getTimestamp("uploaded_at").toInstant(), rs.getString("uploaded_by"), voidedAt != null,
                voidedAt == null ? null : voidedAt.toInstant(), rs.getString("voided_by"), rs.getString("void_reason"));
    };

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DocumentService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A file that was downloaded: what it is, what to call it, and the bytes. */
    public record Download(String filename, String contentType, byte[] bytes) {
    }

    // ----------------------------------------------------------------- upload

    /**
     * @param originalName what the uploader's computer called it; only its last part is used
     * @param uploadedBy   the Keycloak subject, or null
     */
    public DocumentResponse upload(Owner owner, UUID ownerId, String kindName, String originalName, byte[] bytes,
                                   String note, String uploadedBy) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("the file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("the file is " + megabytes(bytes.length) + " MB and the most is 5 MB: "
                    + "scan or save it smaller and try again");
        }
        DocumentKind kind = DocumentKind.parse(kindName);
        if (owner == Owner.PURCHASE_ORDER && !kind.fitsAnOrder()) {
            throw new IllegalArgumentException("a " + kind + " belongs to a consignment, not to a purchase order: "
                    + "the courier and ZRA bill the shipment");
        }
        DocumentType type = DocumentType.detect(bytes);
        String tidyNote = tidyNote(note);

        // Hold the owner still while the document is attached: a draft consignment that is deleted
        // takes its documents with it, and must not do that halfway through this.
        List<Integer> found = jdbc.queryForList("SELECT 1 FROM " + owner.table + " WHERE id = ? FOR SHARE",
                Integer.class, ownerId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException(owner.resource, "id", ownerId.toString());
        }
        Long inUse = jdbc.queryForObject("SELECT count(*) FROM procurement_document WHERE " + owner.column
                + " = ? AND voided_at IS NULL", Long.class, ownerId);
        if (inUse != null && inUse >= MAX_ACTIVE_PER_OWNER) {
            throw new IllegalStateException("This " + owner.label.toLowerCase().replace('_', ' ') + " already has "
                    + MAX_ACTIVE_PER_OWNER + " documents in use; void one that is not needed first.");
        }

        UUID id = UUID.randomUUID();
        String sha = sha256(bytes);
        String filename = tidyFilename(originalName, type);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            jdbc.update("INSERT INTO procurement_document (id, " + owner.column + ", kind, filename, content_type, "
                            + "size_bytes, sha256, content, note, uploaded_at, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, ownerId, kind.name(), filename, type.mediaType(), bytes.length, sha, bytes, tidyNote,
                    Timestamp.from(now), uploadedBy);
        } catch (DuplicateKeyException twice) {
            throw new IllegalStateException("This file is already attached to this "
                    + owner.label.toLowerCase().replace('_', ' ') + ". If it was attached by mistake, void it first.");
        }
        log.info("Stored document id={} owner={} {} kind={} type={} bytes={}", id, owner, ownerId, kind, type, bytes.length);
        return one(id);
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(Owner owner, UUID ownerId, boolean includeVoided) {
        if (jdbc.queryForList("SELECT 1 FROM " + owner.table + " WHERE id = ?", Integer.class, ownerId).isEmpty()) {
            throw new ResourceNotFoundException(owner.resource, "id", ownerId.toString());
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM procurement_document WHERE " + owner.column + " = ?"
                + (includeVoided ? "" : " AND voided_at IS NULL") + " ORDER BY uploaded_at DESC, id", DOCUMENT, ownerId);
    }

    @Transactional(readOnly = true)
    public Download download(UUID id) {
        List<Download> found = jdbc.query("SELECT filename, content_type, content FROM procurement_document WHERE id = ?",
                (rs, n) -> new Download(rs.getString(1), rs.getString(2), rs.getBytes(3)), id);
        if (found.isEmpty()) {
            throw notFound(id);
        }
        return found.get(0);
    }

    // ------------------------------------------------------------------- void

    /** Marks a document as not to be relied on. It stays on record, with who, when and why. */
    public DocumentResponse voidDocument(UUID id, String reason, String voidedBy) {
        int changed = jdbc.update("UPDATE procurement_document SET voided_at = ?, voided_by = ?, void_reason = ? "
                        + "WHERE id = ? AND voided_at IS NULL",
                Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)), voidedBy == null ? "system" : voidedBy,
                reason.strip(), id);
        if (changed == 0) {
            if (jdbc.queryForList("SELECT 1 FROM procurement_document WHERE id = ?", Integer.class, id).isEmpty()) {
                throw notFound(id);
            }
            throw new IllegalStateException("This document is already voided");
        }
        log.info("Voided document id={}", id);
        return one(id);
    }

    // ---------------------------------------------------------------- helpers

    private DocumentResponse one(UUID id) {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM procurement_document WHERE id = ?", DOCUMENT, id);
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Document", "id", id.toString());
    }

    private static String megabytes(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static String tidyNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("note must not contain the NUL character");
        }
        String trimmed = note.strip();
        if (trimmed.length() > MAX_NOTE) {
            throw new IllegalArgumentException("note must be at most " + MAX_NOTE + " characters");
        }
        return trimmed;
    }

    /**
     * What a person sees. Only the last part of whatever path the upload carried (either slash),
     * with control characters and quotes removed, an extension that fits the file (added when the
     * name has none that does), and at most 120 characters in all, the extension kept.
     */
    static String tidyFilename(String original, DocumentType type) {
        String name = original == null ? "" : original;
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(cut + 1);
        StringBuilder clean = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (!Character.isISOControl(c) && c != '"') {
                clean.append(c);
            }
        }
        name = clean.toString().strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "document";
        }
        String extension = type.extensionOf(name);
        String stem;
        if (extension != null) {
            stem = name.substring(0, name.length() - extension.length());
            extension = name.substring(name.length() - extension.length());
        } else {
            stem = name;
            extension = type.extension();
        }
        int room = MAX_FILENAME - extension.length();
        if (stem.length() > room) {
            stem = stem.substring(0, room);
        }
        if (stem.isBlank()) {
            stem = "document";
        }
        return stem + extension;
    }
}
