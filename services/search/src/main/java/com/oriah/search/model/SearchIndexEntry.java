package com.oriah.search.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * One row per indexed product. The {@code document} tsvector column is
 * a GENERATED STORED expression in the DB — JPA never writes it. We
 * only manage title/description/tags here; the {@code created_at} /
 * {@code updated_at} / auditor / soft-delete columns are inherited
 * from {@link BaseEntity}.
 */
@Entity
@Table(name = "search_index")
public class SearchIndexEntry extends BaseEntity {

    @Id
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String tags;

    /**
     * Whether a search may return this product: it is ACTIVE and SELLABLE.
     * Decided by the snapshot that wrote the row ({@link #isListable}); the
     * query reads only this flag.
     */
    @Column(nullable = false)
    private boolean listed;

    protected SearchIndexEntry() {}

    public SearchIndexEntry(UUID variantId, String title, String description, String tags, boolean listed) {
        this.variantId = variantId;
        this.title = title;
        this.description = description;
        this.tags = tags;
        this.listed = listed;
    }

    public UUID variantId() { return variantId; }
    public String title() { return title; }
    public String description() { return description; }
    public String tags() { return tags; }
    public boolean listed() { return listed; }

    public void replace(String title, String description, String tags, boolean listed) {
        this.title = title;
        this.description = description;
        this.tags = tags;
        this.listed = listed;
    }

    /**
     * The one rule for what a search may return: the product is ACTIVE and it
     * is SELLABLE.
     *
     * <p>Strict where it matters. An absent or unrecognised status is not
     * listed, because an index that cannot tell must say nothing rather than
     * show a draft. An absent kind IS read as SELLABLE: every snapshot written
     * before Phase 3 lacks one, and every product that existed then was made to
     * be sold. A kind present but not recognised (a future one) is not listed,
     * so a new kind has to be taught to search before it appears in it.
     */
    public static boolean isListable(String status, String kind) {
        String effectiveKind = kind == null ? "SELLABLE" : kind;
        return "ACTIVE".equals(status) && "SELLABLE".equals(effectiveKind);
    }
}
