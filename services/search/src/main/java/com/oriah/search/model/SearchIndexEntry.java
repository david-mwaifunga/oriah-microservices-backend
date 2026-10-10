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

    protected SearchIndexEntry() {}

    public SearchIndexEntry(UUID variantId, String title, String description, String tags) {
        this.variantId = variantId;
        this.title = title;
        this.description = description;
        this.tags = tags;
    }

    public UUID variantId() { return variantId; }
    public String title() { return title; }
    public String description() { return description; }
    public String tags() { return tags; }

    public void replace(String title, String description, String tags) {
        this.title = title;
        this.description = description;
        this.tags = tags;
    }
}
