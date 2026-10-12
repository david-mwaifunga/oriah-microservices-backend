package com.oriah.catalog.service;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.catalog.model.Category;
import com.oriah.catalog.repository.CategoryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The category tree the storefront navigation is built from.
 *
 * <p>Tags could never do this job: nothing says "Rings" is part of
 * "Jewellery", and nothing stops a typo creating a second "rings". The tree
 * endpoint exists so the nav is one request rather than one request per level.
 */
class CategoryServiceTest {

    private CategoryRepository repository;
    private DefaultCategoryService service;

    private Category jewellery;
    private Category rings;
    private Category necklaces;
    private Category watches;

    @BeforeEach
    void setUp() {
        repository = mock(CategoryRepository.class);
        service = new DefaultCategoryService(repository);

        jewellery = new Category("Jewellery", "Fine pieces.", null);
        rings = new Category("Rings", null, jewellery);
        necklaces = new Category("Necklaces", null, jewellery);
        watches = new Category("Watches", null, null);
    }

    @Test
    @DisplayName("the tree nests children under their roots in one response")
    void treeIsNested() {
        when(repository.findRoots()).thenReturn(List.of(jewellery, watches));
        when(repository.findChildren(jewellery.id())).thenReturn(List.of(rings, necklaces));
        when(repository.findChildren(watches.id())).thenReturn(List.of());
        when(repository.findChildren(rings.id())).thenReturn(List.of());
        when(repository.findChildren(necklaces.id())).thenReturn(List.of());

        var tree = service.tree();

        assertThat(tree).extracting("slug").containsExactly("jewellery", "watches");
        assertThat(tree.getFirst().children()).extracting("slug")
                .containsExactly("rings", "necklaces");
        assertThat(tree.getFirst().description()).isEqualTo("Fine pieces.");
    }

    @Test
    @DisplayName("a leaf's children are an empty list, not null, so clients need no null check")
    void leavesHaveEmptyChildren() {
        when(repository.findRoots()).thenReturn(List.of(watches));
        when(repository.findChildren(watches.id())).thenReturn(List.of());

        assertThat(service.tree().getFirst().children()).isEmpty();
    }

    @Test
    @DisplayName("the recursion is depth-capped, so corrupt parent links cannot hang the nav")
    void treeRecursionIsBounded() {
        // The repository's descendant query is bounded; this one walks
        // children instead, so it needs its own bound. A cycle written by
        // direct SQL -- the state CategoryRepositoryTest deliberately
        // creates -- would otherwise recurse until the stack gave out, on a
        // PUBLIC endpoint.
        when(repository.findRoots()).thenReturn(List.of(jewellery));
        when(repository.findChildren(any(UUID.class))).thenReturn(List.of(rings));

        var tree = service.tree();

        assertThat(tree).hasSize(1);
        int depth = 0;
        var node = tree.getFirst();
        while (node.children() != null && !node.children().isEmpty()) {
            node = node.children().getFirst();
            depth++;
            assertThat(depth).isLessThanOrEqualTo(12);
        }
        assertThat(depth).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("a category is findable by slug, without its children")
    void findBySlug() {
        when(repository.findBySlug("rings")).thenReturn(java.util.Optional.of(rings));

        var found = service.findBySlug("rings").orElseThrow();

        assertThat(found.slug()).isEqualTo("rings");
        assertThat(found.name()).isEqualTo("Rings");
    }

    @Test
    @DisplayName("an unknown slug is empty, so the controller can answer 404")
    void unknownSlugIsEmpty() {
        when(repository.findBySlug("sofas")).thenReturn(java.util.Optional.empty());
        assertThat(service.findBySlug("sofas")).isEmpty();
    }

    @Test
    @DisplayName("descendant ids for a slug include the category itself")
    void descendantIdsIncludeSelf() {
        var ids = List.of(jewellery.id(), rings.id(), necklaces.id());
        when(repository.findBySlug("jewellery")).thenReturn(java.util.Optional.of(jewellery));
        when(repository.findDescendantIds(jewellery.id())).thenReturn(ids);

        assertThat(service.descendantIdsOf("jewellery")).contains(ids);
    }

    @Test
    @DisplayName("an unknown slug yields EMPTY, which is not the same as an empty id list")
    void unknownSlugIsNotAnEmptyFilter() {
        // The distinction decides whether a bad category slug in a URL shows
        // nothing (correct, a 404) or shows the whole catalogue (a failure
        // that looks like success). The service must not resolve an unknown
        // slug to "no filter".
        when(repository.findBySlug("sofas")).thenReturn(java.util.Optional.empty());

        assertThat(service.descendantIdsOf("sofas")).isEmpty();
        verify(repository, never()).findDescendantIds(any(UUID.class));
    }
}
