package com.oriah.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.catalog.model.Category;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findBySlug(String slug);

    @Query("SELECT c FROM Category c WHERE c.parent IS NULL ORDER BY c.position ASC, c.name ASC")
    List<Category> findRoots();

    @Query("SELECT c FROM Category c WHERE c.parent.id = :parentId ORDER BY c.position ASC, c.name ASC")
    List<Category> findChildren(@Param("parentId") UUID parentId);

    /**
     * The given category plus every category beneath it.
     *
     * <p>Native because JPQL has no recursion, and this has to exist: filtering
     * the storefront by "Jewellery" must return rings and necklaces too, or a
     * parent category is a dead link.
     *
     * <h2>Why it is bounded twice</h2>
     *
     * The depth cap is what delivers termination, and it is the one the test
     * pins: removing the visited-path guard while keeping {@code depth < 10}
     * leaves {@code CategoryRepositoryTest} entirely green, which was measured
     * rather than assumed. So the guard is not what makes the cycle test pass.
     *
     * <p>It earns its place for a different reason: without it a cyclic,
     * branching tree generates rows combinatorially inside the CTE for ten
     * levels before {@code DISTINCT} collapses them, so the query returns the
     * right answer having done an arbitrary amount of work to get there. The
     * guard keeps the work proportional to the tree. That is a reasoned
     * justification, not a tested one — nothing observable through this
     * interface distinguishes the two, since both produce the same result set.
     *
     * <p>The service already refuses to create a cycle by walking ancestors,
     * so why guard here at all: because that only protects writes that went
     * through the service. A row edited by hand, a bad data load or a future
     * bug can still produce one, and the {@code categories_not_own_parent}
     * CHECK only catches a category parented to itself. An unbounded recursive
     * CTE against cyclic data does not return — it holds the connection, then
     * exhausts the pool, then takes the service down. Making the read path
     * safe independently of the write path converts that outage into a
     * slightly short answer. {@code CategoryRepositoryTest} proves termination
     * by writing a cycle with native SQL, bypassing the service on purpose.
     */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT c.id, ARRAY[c.id] AS path, 0 AS depth
                FROM categories c
                WHERE c.id = :rootId
              UNION ALL
                SELECT c.id, d.path || c.id, d.depth + 1
                FROM categories c
                JOIN descendants d ON c.parent_id = d.id
                WHERE d.depth < 10
                  AND NOT c.id = ANY(d.path)
            )
            SELECT DISTINCT id FROM descendants
            """, nativeQuery = true)
    List<UUID> findDescendantIds(@Param("rootId") UUID rootId);
}
