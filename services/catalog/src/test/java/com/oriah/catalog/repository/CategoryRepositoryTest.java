package com.oriah.catalog.repository;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.model.Category;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Descendant resolution for category filtering.
 *
 * <p>Filtering the storefront by "Jewellery" has to return rings and necklaces
 * too, or a parent category is a dead link. Postgres recursive CTEs are the
 * only way to express that, which is why this is a native query — JPQL has no
 * recursion.
 *
 * <p>The query is deliberately bounded on BOTH axes, and the cycle test below
 * is the reason. The service prevents cycles by walking ancestors before
 * accepting a parent, but that only protects writes that go through the
 * service. A row edited by hand, a bad data load, or a future bug can still
 * produce A -> B -> A, and the database CHECK only catches a category parented
 * to itself. An unbounded CTE against cyclic data does not return — it
 * consumes the connection, then the pool, then the service. So the read path
 * is made safe independently of whether the write path held.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository repository;

    @Autowired
    private EntityManager entityManager;

    /** jewellery -> rings -> solitaires, plus an unrelated watches tree. */
    private record Tree(Category jewellery, Category rings, Category solitaires, Category watches) {
    }

    private Tree seedTree() {
        var jewellery = repository.save(new Category("Jewellery", null, null));
        var rings = repository.save(new Category("Rings", null, jewellery));
        var solitaires = repository.save(new Category("Solitaires", null, rings));
        var watches = repository.save(new Category("Watches", null, null));
        entityManager.flush();
        return new Tree(jewellery, rings, solitaires, watches);
    }

    @Test
    @DisplayName("a leaf resolves to just itself")
    void leafIsItsOwnDescendant() {
        var tree = seedTree();
        assertThat(repository.findDescendantIds(tree.solitaires().id()))
                .containsExactly(tree.solitaires().id());
    }

    @Test
    @DisplayName("a parent resolves to itself plus children and grandchildren")
    void parentIncludesWholeSubtree() {
        var tree = seedTree();

        // Without the grandchild, filtering by "Jewellery" would silently omit
        // solitaires, and the deeper a product sits the more likely it is to
        // be missing from the parent category it belongs to.
        assertThat(repository.findDescendantIds(tree.jewellery().id()))
                .containsExactlyInAnyOrder(
                        tree.jewellery().id(), tree.rings().id(), tree.solitaires().id());
    }

    @Test
    @DisplayName("a sibling tree is not included")
    void unrelatedTreesExcluded() {
        var tree = seedTree();
        assertThat(repository.findDescendantIds(tree.jewellery().id()))
                .doesNotContain(tree.watches().id());
    }

    @Test
    @DisplayName("an unknown id resolves to nothing rather than to everything")
    void unknownIdIsEmpty() {
        seedTree();
        // Returning every category for an unknown filter would turn a typo in
        // a URL into "show the whole catalogue", which looks like it worked.
        assertThat(repository.findDescendantIds(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a cycle written straight into the database still terminates")
    void cycleTerminates() {
        var tree = seedTree();

        // Bypassing the service entirely, which is the whole point: this is
        // the state the service is supposed to prevent, and the read path must
        // survive it anyway. The CHECK constraint permits this because no row
        // is its own parent.
        entityManager.createNativeQuery(
                        "update categories set parent_id = :child where id = :parent")
                .setParameter("child", tree.solitaires().id())
                .setParameter("parent", tree.jewellery().id())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // jewellery -> rings -> solitaires -> jewellery. An unbounded CTE
        // never returns here.
        List<UUID> descendants = repository.findDescendantIds(tree.jewellery().id());

        assertThat(descendants)
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(32)
                .contains(tree.jewellery().id(), tree.rings().id(), tree.solitaires().id());
    }

    @Test
    @DisplayName("slugs are generated and unique across the whole tree")
    void slugsAreGenerated() {
        var tree = seedTree();
        assertThat(tree.jewellery().slug()).isEqualTo("jewellery");
        assertThat(tree.solitaires().slug()).isEqualTo("solitaires");
        assertThat(repository.findBySlug("rings")).isPresent();
        assertThat(repository.findBySlug("nope")).isEmpty();
    }

    @Test
    @DisplayName("root categories can be listed for the top-level nav")
    void rootsAreListable() {
        var tree = seedTree();
        assertThat(repository.findRoots())
                .extracting(Category::id)
                .containsExactlyInAnyOrder(tree.jewellery().id(), tree.watches().id());
    }
}
