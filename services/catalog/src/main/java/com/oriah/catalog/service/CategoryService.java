package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.oriah.catalog.dto.CategoryCreateRequest;
import com.oriah.catalog.dto.CategoryResponse;
import com.oriah.catalog.dto.CategoryUpdateRequest;

/**
 * Reads over the category tree.
 *
 * <p>Returns wire types rather than entities, and that is not a stylistic
 * choice: a Category's parent is lazy, so handing the entity to a controller
 * and mapping it there is the LazyInitializationException this service already
 * shipped once on the product read path.
 */
public interface CategoryService {

    /** The whole tree, roots first, each with its children nested. */
    List<CategoryResponse> tree();

    Optional<CategoryResponse> findBySlug(String slug);

    /**
     * The ids to filter products by when a shopper picks this category:
     * the category itself plus everything beneath it.
     *
     * @return empty when no category has that slug -- which the caller must
     *         NOT conflate with an empty id list. An unknown slug is a 404;
     *         a category with no descendants still matches itself.
     */
    Optional<List<UUID>> descendantIdsOf(String slug);

    /**
     * Creates a category, deriving its slug from its name.
     *
     * @throws IllegalArgumentException the name is blank, or the parent id
     *         names no category -- 400 rather than a foreign key violation
     *         rendered as a 500
     */
    CategoryResponse create(CategoryCreateRequest request);

    /**
     * Applies a partial edit. Null fields are left alone.
     *
     * <p>Renaming does NOT re-derive the slug: it is in storefront URLs and in
     * anyone's bookmarks, so the label and the address are deliberately
     * separate concerns.
     *
     * @throws IllegalArgumentException the move would create a cycle, or the
     *         parent id names no category
     */
    Optional<CategoryResponse> update(UUID id, CategoryUpdateRequest request);

    /**
     * Removes a category.
     *
     * <p>{@code product_categories} cascades, so this removes the LINKS and
     * never a product: a taxonomy edit must not be able to delete something
     * sellable. Child categories are re-parented to this one's parent rather
     * than deleted, for the same reason -- removing "Rings" should not silently
     * remove "Signet" and everything filed under it.
     *
     * @return false when there is no such category
     */
    boolean delete(UUID id);
}
