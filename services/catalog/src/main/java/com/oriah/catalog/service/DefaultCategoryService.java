package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.dto.CategoryCreateRequest;
import com.oriah.catalog.dto.CategoryUpdateRequest;
import com.oriah.catalog.dto.CategoryResponse;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.repository.ProductRepository;
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
    private final ProductRepository products;

    public DefaultCategoryService(CategoryRepository categories, ProductRepository products) {
        this.products = products;
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

    @Override
    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        Category parent = resolveParent(request.parentId());
        // The entity derives the slug and refuses a blank name, so neither is
        // re-implemented here.
        Category created = new Category(request.name(), request.description(), parent);
        Category saved = categories.save(created);
        log.info("Created category id={} slug={} parent={}",
                saved.id(), saved.slug(), parent == null ? "(root)" : parent.slug());
        return toResponseWithoutChildren(saved);
    }

    @Override
    @Transactional
    public Optional<CategoryResponse> update(UUID id, CategoryUpdateRequest request) {
        return categories.findById(id).map(category -> {
            if (request.name() != null) {
                // Renames the LABEL only. See CategoryUpdateRequest for why the
                // slug is deliberately left alone.
                category.rename(request.name());
            }
            if (request.description() != null) {
                category.updateDescription(request.description());
            }
            if (request.position() != null) {
                category.setPosition(request.position());
            }
            if (request.parentId() != null) {
                // reparent() walks the prospective ancestry and throws on a
                // cycle: the database CHECK only catches a category parented
                // directly to itself.
                category.reparent(resolveParent(request.parentId()));
            }
            log.info("Updated category id={} slug={}", category.id(), category.slug());
            return toResponseWithoutChildren(category);
        });
    }

    @Override
    @Transactional
    public boolean delete(UUID id) {
        return categories.findById(id).map(category -> {
            // Children move up rather than disappearing. product_categories
            // cascades, so the product LINKS go and the products do not --
            // a taxonomy edit must never remove something sellable.
            // Optional, because a root category has none.
            Category newParent = category.parent().orElse(null);
            List<Category> children = categories.findChildren(id);
            for (Category child : children) {
                child.reparent(newParent);
            }
            if (!children.isEmpty()) {
                log.info("Re-parented {} child categor{} of {} before deleting it",
                        children.size(), children.size() == 1 ? "y" : "ies", category.slug());
            }
            // Clear the breadcrumb from any product that used this category
            // as its primary. product_categories cascades on its own, but
            // products.primary_category_id is a plain reference: without this
            // the delete violates it and the operator sees a 409 for a
            // taxonomy edit that should simply have worked -- or, worse, we
            // would be tempted to cascade it and take sellable products with
            // it. assignCategories re-checks the invariant, with the remaining
            // memberships minus this one.
            for (Product affected : products.findByPrimaryCategoryId(id)) {
                var remaining = new java.util.LinkedHashSet<>(affected.categories());
                remaining.remove(category);
                affected.assignCategories(remaining, null);
                log.info("Cleared primary category {} from product {}", category.slug(), affected.id());
            }
            categories.delete(category);
            log.info("Deleted category id={} slug={}", id, category.slug());
            return true;
        }).orElse(false);
    }

    /**
     * Resolves a parent id, or null for a root.
     *
     * <p>Checked here rather than left to the foreign key: an unknown id would
     * otherwise reach Postgres and come back as a constraint violation
     * rendered 500, when it is plainly a bad request.
     */
    private Category resolveParent(UUID parentId) {
        if (parentId == null) {
            return null;
        }
        return categories.findById(parentId).orElseThrow(() ->
                new IllegalArgumentException("no category with id " + parentId));
    }

    /**
     * A single category, without walking its children.
     *
     * <p>The write endpoints answer with the node that changed. Returning its
     * subtree would mean a recursive read on every edit for information the
     * caller already has from {@code tree()}.
     */
    private static CategoryResponse toResponseWithoutChildren(Category category) {
        return new CategoryResponse(
                category.id(),
                category.slug(),
                category.name(),
                category.description().orElse(null),
                category.position(),
                List.of());
    }
}
