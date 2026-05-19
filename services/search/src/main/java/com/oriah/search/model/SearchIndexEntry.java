package com.oriah.search.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per indexed product. The {@code document} tsvector column is
 * a GENERATED STORED expression in the DB — JPA never writes it. We
 * only manage title/description/tags here.
 */
@Entity
@Table(name = "search_index")
public class SearchIndexEntry {

    @Id
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String tags;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SearchIndexEntry() {}

    public SearchIndexEntry(UUID variantId, String title, String description, String tags) {
        this.variantId = variantId;
        this.title = title;
        this.description = description;
        this.tags = tags;
        this.updatedAt = Instant.now();
    }

    public UUID variantId() { return variantId; }
    public String title() { return title; }
    public String description() { return description; }
    public String tags() { return tags; }
    public Instant updatedAt() { return updatedAt; }

    public void replace(String title, String description, String tags) {
        this.title = title;
        this.description = description;
        this.tags = tags;
        this.updatedAt = Instant.now();
    }
}
