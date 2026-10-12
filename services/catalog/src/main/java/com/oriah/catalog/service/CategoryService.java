package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.oriah.catalog.dto.CategoryResponse;

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
}
