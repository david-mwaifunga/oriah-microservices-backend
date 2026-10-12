package com.oriah.catalog.model;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * An explicit, hierarchical category: "Rings" under "Jewellery", "Dive
 * Watches" under "Watches".
 *
 * <h2>Why this exists when tags already did</h2>
 *
 * Tags are a flat, open set — good for cross-cutting labels like "new-in" or
 * "gift", useless for navigation, because nothing says "Rings" is part of
 * "Jewellery" and nothing stops a typo creating a second "rings". Categories
 * are a closed, ordered, parented set that a storefront nav and a breadcrumb
 * can be built on. Tags are kept exactly as they were; this is in addition.
 *
 * <h2>Slugs are globally unique, deliberately</h2>
 *
 * Not {@code (parent_id, slug)}. URLs are flat — {@code /categories/{slug}} —
 * which is what a two-level jewellery and watch taxonomy needs. The cost is
 * that "rings" cannot exist under two different parents. Path-based URLs with
 * full-path uniqueness would lift that restriction and cost materially more
 * complexity, for a case this catalogue does not have.
 *
 * <h2>Cycles</h2>
 *
 * The {@code categories_not_own_parent} CHECK catches only self-parenting.
 * A longer cycle (A to B to A) is prevented here, by walking ancestors before
 * accepting a parent. That is the preventive half; the protective half is that
 * the descendant query is bounded by both a depth cap and a visited-path
 * guard, so a cycle introduced by direct SQL cannot hang a storefront request.
 */
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    /**
     * Bound on both the ancestor walk and {@link #depth()}. It is not a
     * product rule -- two levels is all this taxonomy needs -- it is a
     * guard so that corrupt data (a cycle written by direct SQL) cannot turn
     * a traversal into a hang.
     */
    private static final int MAX_DEPTH = 10;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(nullable = false)
    private int position;

    protected Category() {
        // For JPA hydration only.
    }

    public Category(String name, String description, Category parent) {
        this(UUID.randomUUID(), name, null, description, parent);
    }

    public Category(UUID id, String name, String slug, String description, Category parent) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("category name must not be blank");
        }
        this.id = id;
        this.name = name.trim();
        this.slug = slug == null || slug.isBlank() ? Slug.from(this.name) : slug.trim();
        this.description = description;
        this.position = 0;
        // Assigned directly rather than through reparent(): a brand-new
        // category cannot be its own ancestor, so there is no cycle to check
        // at construction. reparent() is for moves, which can.
        this.parent = parent;
    }

    public UUID id() {
        return id;
    }

    public Optional<Category> parent() {
        return Optional.ofNullable(parent);
    }

    public String slug() {
        return slug;
    }

    public String name() {
        return name;
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public int position() {
        return position;
    }

    public void setPosition(int newPosition) {
        this.position = newPosition;
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("category name must not be blank");
        }
        this.name = newName.trim();
    }

    public void updateDescription(String newDescription) {
        this.description = newDescription;
    }

    /**
     * Moves this category under a new parent, or to the root with null.
     *
     * @throws IllegalArgumentException if the move would create a cycle
     */
    public void reparent(Category newParent) {
        // Walks the prospective ancestry looking for this category. The
        // database CHECK only catches a category parented to itself, so a
        // two-step cycle (A under B, B under A) has to be caught here. The
        // walk is bounded by MAX_DEPTH so that pre-existing bad data cannot
        // turn this into an infinite loop -- the same defensive posture the
        // descendant query takes.
        Category ancestor = newParent;
        int hops = 0;
        while (ancestor != null) {
            if (ancestor == this || (id != null && id.equals(ancestor.id))) {
                throw new IllegalArgumentException(
                        "cannot parent category '" + slug + "' under itself or its own descendant");
            }
            if (++hops > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "category hierarchy deeper than " + MAX_DEPTH + " levels");
            }
            ancestor = ancestor.parent;
        }
        this.parent = newParent;
    }

    /** Distance from the root; 0 for a top-level category. */
    public int depth() {
        int depth = 0;
        Category ancestor = parent;
        while (ancestor != null && depth <= MAX_DEPTH) {
            depth++;
            ancestor = ancestor.parent;
        }
        return depth;
    }
}
