package com.oriah.catalog.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * Product aggregate root for the catalog domain.
 *
 * <p>This is both the JPA entity (mapped to {@code products} +
 * {@code product_tags} tables by {@code V1__create_products.sql}) and the
 * in-memory domain model. The class enforces its invariants in the
 * constructor and update methods so business rules stay close to the data.
 *
 * <p>Identity is a UUID generated at construction (not by the database) so
 * callers can refer to a product before it has been persisted.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_tags", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "tag", nullable = false)
    private List<String> tags = new ArrayList<>();

    protected Product() {
        // For JPA hydration only — do not call from application code.
    }

    public Product(String title) {
        this(UUID.randomUUID(), title, null, null);
    }

    public Product(String title, String description) {
        this(UUID.randomUUID(), title, description, null);
    }

    public Product(String title, String description, List<String> tags) {
        this(UUID.randomUUID(), title, description, tags);
    }

    /**
     * Reconstitution constructor used when the id is already known —
     * e.g. mapping from a persisted record, mapping from a request DTO that
     * supplies one, or constructing test fixtures with a deterministic id.
     *
     * <p>Public because, under the layer-by-layer package layout, controllers
     * and tests in other packages legitimately need to materialize a Product
     * with a specific id. With aggregate-per-package this could stay
     * package-private, but the encapsulation gain isn't worth duplicating
     * factory methods across packages.
     */
    public Product(UUID id, String title, String description, List<String> tags) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.id = id;
        this.title = title;
        this.description = description;
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public UUID id() {
        return id;
    }

    public String title() {
        return title;
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public List<String> tags() {
        return List.copyOf(tags);
    }

    /**
     * Updates the product's title. Same invariant as construction:
     * must not be blank.
     */
    public void updateTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = newTitle;
    }

    /**
     * Replaces the product's description. Null is rejected via DTO-level
     * validation in {@code ProductUpdateRequest}; if called with null
     * directly, the field is set to null (use this only for non-null
     * updates routed through the service).
     */
    public void updateDescription(String newDescription) {
        this.description = newDescription;
    }

    /**
     * Replaces the product's tag list with a fresh copy.
     */
    public void updateTags(List<String> newTags) {
        this.tags = newTags == null ? new ArrayList<>() : new ArrayList<>(newTags);
    }
}
