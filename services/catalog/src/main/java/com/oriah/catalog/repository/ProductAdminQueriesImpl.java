package com.oriah.catalog.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductStatus;

/**
 * Hand-built SQL for the same reason as {@link ProductStorefrontQueriesImpl}:
 * the filters are optional and a derived query cannot say "this condition, only
 * when supplied". Everything interpolated into the statement is a fixed
 * fragment; every caller-supplied value is a bind parameter, so none of it can
 * change the statement.
 */
public class ProductAdminQueriesImpl implements ProductAdminQueries {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<UUID> findAdminIds(ProductKind kind, ProductStatus status, String text, int page, int size) {
        var parameters = new LinkedHashMap<String, Object>();
        String sql = "SELECT p.id FROM products p WHERE " + whereClause(kind, status, text, parameters)
                + " ORDER BY p.created_at DESC NULLS LAST, p.id DESC LIMIT :size OFFSET :offset";

        Query query = entityManager.createNativeQuery(sql, UUID.class);
        parameters.forEach(query::setParameter);
        query.setParameter("size", size);
        query.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<UUID> ids = query.getResultList();
        return ids;
    }

    @Override
    public long countAdmin(ProductKind kind, ProductStatus status, String text) {
        var parameters = new LinkedHashMap<String, Object>();
        String sql = "SELECT COUNT(*) FROM products p WHERE " + whereClause(kind, status, text, parameters);

        Query query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    private static String whereClause(ProductKind kind, ProductStatus status, String text,
                                      Map<String, Object> parameters) {
        var conditions = new ArrayList<String>();
        // A deleted product is gone from every read path, the operator's too:
        // an edit through the admin read would otherwise resurrect it.
        conditions.add("NOT p.is_deleted");
        if (kind != null) {
            conditions.add("p.kind = :kind");
            parameters.put("kind", kind.name());
        }
        if (status != null) {
            conditions.add("p.status = :status");
            parameters.put("status", status.name());
        }
        if (text != null && !text.isBlank()) {
            // The SKU is searched as well as the title: a consignment line is
            // keyed from the supplier's paperwork, which names the SKU.
            // Folded to lower case by the DATABASE on both sides (LOWER of the
            // column and of the pattern), so the two can never disagree about a
            // non-ASCII letter the way a Java-side fold and a Postgres one could.
            conditions.add("""
                    (LOWER(p.title) LIKE LOWER(:pattern) ESCAPE '\\'
                     OR EXISTS (SELECT 1 FROM product_variants v
                                 WHERE v.product_id = p.id AND LOWER(v.sku) LIKE LOWER(:pattern) ESCAPE '\\'))""");
            parameters.put("pattern", "%" + escapeLike(text.trim()) + "%");
        }
        return String.join(" AND ", conditions);
    }

    /**
     * The text a person typed, made safe to put inside a LIKE pattern: a percent
     * sign or an underscore in it means that character, not "anything". Without
     * this, searching for "100%" would match every product and "_" every one
     * with a title.
     */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
