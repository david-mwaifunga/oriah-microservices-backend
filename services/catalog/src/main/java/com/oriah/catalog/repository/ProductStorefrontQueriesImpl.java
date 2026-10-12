package com.oriah.catalog.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductSort;
import com.oriah.catalog.model.StorefrontFilter;

/**
 * The storefront list query.
 *
 * <h2>Why this is hand-built SQL and not a derived query or a Specification</h2>
 *
 * Two requirements collide. The list must filter and sort on <em>the cheapest
 * purchasable variant's price</em>, which is an aggregate over a child table
 * restricted to {@code active AND unit_price > 0} — not something a derived
 * query name or a static JPQL string can express. And the ORDER BY has to vary
 * per request, which rules out {@code @Query}.
 *
 * <p>So the SQL is assembled here. Everything interpolated into the string is
 * either a fixed fragment chosen by a {@link ProductSort} enum constant or a
 * named parameter placeholder; no caller-supplied text ever reaches the
 * statement. That is why {@code ProductSort} is a closed enum rather than a
 * sort string — a client-controlled ORDER BY expression is an injection.
 *
 * <h2>Two queries, not one</h2>
 *
 * The id query runs the filters, sort and paging; a second query loads those
 * products with their collections. A single query with {@code left join fetch}
 * across variants, images and categories would produce a cartesian product —
 * a 24-card grid where each product has 3 variants, 4 images and 2 categories
 * is 24 × 24 rows to assemble 24 objects — and, worse, a fetch join combined
 * with {@code LIMIT} makes Hibernate paginate IN MEMORY (HHH90003004), so the
 * database would return the whole catalogue on every page.
 */
public class ProductStorefrontQueriesImpl implements ProductStorefrontQueries {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The cheapest price a customer can actually pay for this product. Used
     * for both the price filter and the price sort, so it is written once.
     *
     * <p>{@code active AND unit_price > 0} is the same definition as
     * {@code ProductVariant.isPurchasable()}: zero is V3's marker for "nobody
     * has priced this yet", not a price. Filtering on a deactivated or
     * unpriced variant would advertise a figure the customer cannot get.
     */
    private static final String LOWEST_PURCHASABLE_PRICE = """
            (SELECT MIN(pv.unit_price) FROM product_variants pv
              WHERE pv.product_id = p.id AND pv.active AND pv.unit_price > 0)""";

    @Override
    public List<UUID> findStorefrontIds(StorefrontFilter filter, ProductSort sort, int page, int size) {
        if (matchesNothing(filter)) {
            return List.of();
        }
        var parameters = new LinkedHashMap<String, Object>();
        String sql = "SELECT p.id FROM products p WHERE " + whereClause(filter, parameters)
                + " ORDER BY " + orderByFor(sort)
                + " LIMIT :size OFFSET :offset";

        Query query = entityManager.createNativeQuery(sql, UUID.class);
        parameters.forEach(query::setParameter);
        query.setParameter("size", size);
        query.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<UUID> ids = query.getResultList();
        return ids;
    }

    @Override
    public long countStorefront(StorefrontFilter filter) {
        if (matchesNothing(filter)) {
            return 0L;
        }
        var parameters = new LinkedHashMap<String, Object>();
        String sql = "SELECT COUNT(*) FROM products p WHERE " + whereClause(filter, parameters);

        Query query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * An EMPTY category filter matches nothing, and is handled before any SQL
     * runs for two reasons.
     *
     * <p>The semantic one: resolving an unknown category slug yields no
     * descendant ids, and treating that as "no filter" would turn a typo in a
     * URL into "here is the entire catalogue" — a failure indistinguishable
     * from success. {@code StorefrontFilter.categoryIds} is therefore
     * three-valued, and null and empty must not collapse.
     *
     * <p>The mechanical one: {@code IN ()} is a syntax error, so an empty
     * collection cannot be passed to the database at all.
     */
    private static boolean matchesNothing(StorefrontFilter filter) {
        return filter.hasCategoryFilter() && filter.categoryIds().isEmpty();
    }

    /**
     * Builds the shared WHERE clause, collecting bind values as it goes so the
     * id query and the count query cannot drift apart.
     */
    private static String whereClause(StorefrontFilter filter, Map<String, Object> parameters) {
        var conditions = new ArrayList<String>();

        // Status first: the storefront shows ACTIVE only. DRAFT is where V3
        // left the entire pre-existing catalogue, and ARCHIVED is withdrawn.
        conditions.add("p.status = 'ACTIVE'");

        // A product with nothing purchasable has no price to render, so it has
        // no card. publish() already refuses to create this state, but direct
        // SQL can, and a priceless card is not something to render.
        conditions.add("""
                EXISTS (SELECT 1 FROM product_variants pv
                         WHERE pv.product_id = p.id AND pv.active AND pv.unit_price > 0)""");

        if (filter.typeCode() != null) {
            conditions.add("p.product_type_code = :typeCode");
            parameters.put("typeCode", filter.typeCode());
        }
        if (filter.tag() != null) {
            conditions.add("""
                    EXISTS (SELECT 1 FROM product_tags pt
                             WHERE pt.product_id = p.id AND pt.tag = :tag)""");
            parameters.put("tag", filter.tag());
        }
        if (filter.hasCategoryFilter()) {
            // The caller passes the already-resolved descendant set, so
            // filtering by a parent category includes everything beneath it
            // without this query needing to recurse.
            conditions.add("""
                    EXISTS (SELECT 1 FROM product_categories pc
                             WHERE pc.product_id = p.id AND pc.category_id IN (:categoryIds))""");
            parameters.put("categoryIds", filter.categoryIds());
        }
        if (filter.minPrice() != null) {
            conditions.add(LOWEST_PURCHASABLE_PRICE + " >= :minPrice");
            parameters.put("minPrice", filter.minPrice());
        }
        if (filter.maxPrice() != null) {
            conditions.add(LOWEST_PURCHASABLE_PRICE + " <= :maxPrice");
            parameters.put("maxPrice", filter.maxPrice());
        }
        return String.join(" AND ", conditions);
    }

    /**
     * Fixed SQL per enum constant. Every ordering ends in an id tiebreak:
     * without one, two products sharing a price (or a null created_at) can
     * swap places between page 1 and page 2, so an item is shown twice and
     * another never — the classic unstable-pagination bug.
     */
    private static String orderByFor(ProductSort sort) {
        return switch (sort) {
            // created_at is nullable by design in this codebase, so NULLS LAST
            // keeps rows written outside an auditing context at the end rather
            // than at an arbitrary position.
            case NEWEST -> "p.created_at DESC NULLS LAST, p.id DESC";
            case PRICE_ASC -> LOWEST_PURCHASABLE_PRICE + " ASC, p.id ASC";
            case PRICE_DESC -> LOWEST_PURCHASABLE_PRICE + " DESC, p.id DESC";
            case TITLE_ASC -> "p.title ASC, p.id ASC";
        };
    }

    /**
     * Loads the listed products with their variants already initialised, in
     * the order the id query returned.
     *
     * <p>Variants are fetch-joined because every card needs a price from them,
     * so they are never optional. Images and categories are left to
     * {@code @BatchSize}, which loads each collection for the whole page in
     * one additional query — three queries per page in total, instead of the
     * cartesian product a triple fetch join would build.
     *
     * <p>The database does not preserve the {@code IN} list's order, so the
     * result is re-sorted here. Without this the carefully chosen sort would
     * survive the id query and then be discarded.
     */
    @Override
    public List<Product> findAllWithDetailByIdIn(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Product> loaded = entityManager.createQuery("""
                        SELECT DISTINCT p FROM Product p
                        LEFT JOIN FETCH p.variants
                        WHERE p.id IN :ids
                        """, Product.class)
                .setParameter("ids", ids)
                .getResultList();

        // Touched here, inside the repository's transaction, so the service
        // and controller can map freely afterwards. Mapping entities outside
        // the transaction that loaded them is a LazyInitializationException
        // this codebase has already shipped once.
        for (Product product : loaded) {
            product.images().size();
            product.categories().size();
        }

        Map<UUID, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            order.put(ids.get(i), i);
        }
        loaded.sort(Comparator.comparingInt(p -> order.getOrDefault(p.id(), Integer.MAX_VALUE)));
        return loaded;
    }
}
