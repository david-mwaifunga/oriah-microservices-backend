package com.oriah.catalog.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Which categories a product is in, counting every category above them.
 *
 * <p>A category promotion covers "one category with its sub-categories", so a ring filed under Rings (which sits under Jewellery) is covered by
 * a promotion on Jewellery. The category tree is catalog's own, so catalog works this out and the promotion service never sees it.
 *
 * <p>The walk upward is bounded twice, like {@link CategoryRepository#findDescendantIds}: a depth cap and a visited-path guard, so a cycle
 * written by hand (the service refuses to create one, a bad data load might) ends the walk with a slightly short answer instead of holding a
 * connection until the pool is gone.
 */
@Repository
public class CategoryAncestry {

    private static final String UPWARDS = """
            WITH RECURSIVE up AS (
                SELECT pc.product_id, c.id, c.parent_id, ARRAY[c.id] AS path, 0 AS depth
                FROM product_categories pc
                JOIN categories c ON c.id = pc.category_id
                WHERE pc.product_id = ANY(?)
              UNION ALL
                SELECT u.product_id, p.id, p.parent_id, u.path || p.id, u.depth + 1
                FROM up u
                JOIN categories p ON p.id = u.parent_id
                WHERE u.depth < 10
                  AND NOT p.id = ANY(u.path)
            )
            SELECT DISTINCT product_id, id FROM up
            """;

    private final JdbcTemplate jdbc;

    public CategoryAncestry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return one entry for every product asked about: the categories it is in and every category above them; empty for a product in none
     */
    public Map<UUID, Set<UUID>> categoriesWithAncestors(Collection<UUID> productIds) {
        Map<UUID, Set<UUID>> answer = new LinkedHashMap<>();
        for (UUID id : productIds) {
            answer.put(id, new LinkedHashSet<>());
        }
        if (answer.isEmpty()) {
            return answer;
        }
        List<UUID> distinct = List.copyOf(answer.keySet());
        jdbc.query(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(UPWARDS);
            ps.setArray(1, con.createArrayOf("uuid", distinct.toArray()));
            return ps;
        }, rs -> {
            answer.get(rs.getObject("product_id", UUID.class)).add(rs.getObject("id", UUID.class));
        });
        return answer;
    }
}
