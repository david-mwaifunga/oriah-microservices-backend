package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.dto.CategoryResponse;
import com.oriah.catalog.model.Category;
import com.oriah.catalog.repository.CategoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads over the category tree.
 *
 * <p>Maps to wire types inside its own transaction. A {@link Category}'s
 * parent is lazy, so returning the entity and mapping it in a controller is
 * the {@code LazyInitializationException} the product read path already
 * shipped once.
 */
@Service
@Slf4j
public class DefaultCategoryService implements CategoryService {

    /**
     * Bound on the tree walk below.
     *
     * <p>{@code CategoryRepository.findDescendantIds} is bounded in SQL, but
     * this method walks children in Java and needs its own guard. The service
     * refuses to create a cycle and the {@code categories_not_own_parent}
     * CHECK catches self-parenting, but neither protects against a parent link
     * written by hand — the exact state {@code CategoryRepositoryTest} creates
     * on purpose. Unbounded recursion there would blow the stack on a PUBLIC
     * endpoint, so the nav returns a short tree instead of a 500.
     */
    private static final int MAX_DEPTH = 10;

    private final CategoryRepository categories;

    public DefaultCategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> tree() {
        log.debug("category tree requested");
        return categories.findRoots().stream()
                .map(root -> toNode(root, 0))
                .toList();
    }

    private CategoryResponse toNode(Category category, int depth) {
        // At the cap, children are reported as empty rather than as null, so a
        // client never has to distinguish "a leaf" from "we stopped looking".
        List<CategoryResponse> children = depth >= MAX_DEPTH
                ? List.of()
                : categories.findChildren(category.id()).stream()
                        .map(child -> toNode(child, depth + 1))
                        .toList();

        return new CategoryResponse(
                category.id(),
                category.slug(),
                category.name(),
                category.description().orElse(null),
                category.position(),
                children);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CategoryResponse> findBySlug(String slug) {
        return categories.findBySlug(slug)
                // Children omitted: this answers "which category is this",
                // and a caller that wants the subtree asks for the tree.
                .map(c -> new CategoryResponse(
                        c.id(), c.slug(), c.name(), c.description().orElse(null),
                        c.position(), null));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code Optional.empty()} for an unknown slug and never an
     * empty list, and the difference is the whole point. An empty id list is a
     * filter that matches nothing; "no filter" would match everything. If this
     * collapsed the two, a typo in a category URL would render the entire
     * catalogue and look like it had worked.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<List<UUID>> descendantIdsOf(String slug) {
        return categories.findBySlug(slug)
                .map(category -> categories.findDescendantIds(category.id()));
    }
}
