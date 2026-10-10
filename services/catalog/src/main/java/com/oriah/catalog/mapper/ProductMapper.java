package com.oriah.catalog.mapper;

import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductWithAvailability;

/**
 * Entity → DTO mapping for the catalog domain.
 *
 * <p>The course's {@code mapper/XxxMapper} convention, and in this one service
 * it also fixes a real dependency inversion rather than just moving code.
 *
 * <h2>What it fixes</h2>
 *
 * <p>These two methods used to be static factories on {@link ProductResponse}
 * itself, which is the idiomatic Java shape and is what the other eleven
 * services still do. Here it had a cost the others do not: the second overload
 * takes {@link ProductWithAvailability}, a <strong>service-layer</strong> type,
 * so {@code com.oriah.catalog.dto} imported {@code com.oriah.catalog.service}.
 * The wire format depended on the orchestration layer, which is backwards — a
 * DTO is the boundary and should depend on nothing above it.
 *
 * <p>Moving the mapping here makes the dependencies point one way:
 * {@code mapper} knows about {@code dto}, {@code model} and {@code service};
 * none of them knows about {@code mapper}. An inventory of all 12 services
 * found this was the <em>only</em> such inversion, which is why catalog is the
 * only service that gets a mapper — see the divergence table in
 * {@code docs/REFACTOR_SECTION12.md}. Creating eleven more to move
 * already-correct static factories into a less idiomatic shape would be churn
 * with no defect behind it.
 *
 * <h2>Why static, and why no {@code MapStruct}</h2>
 *
 * <p>Static because there is no state and nothing to inject; a Spring bean here
 * would add a lifecycle to a pure function. No MapStruct because the mapping is
 * not mechanical: {@code description} is an {@code Optional} on the entity and
 * a nullable field on the wire, and {@code availability} is deliberately absent
 * on list responses and present on the single-product read. A generator would
 * need overrides for both, which is more configuration than the eight lines it
 * would replace.
 */
public final class ProductMapper {

    private ProductMapper() {
        // Static mapping only; nothing to construct.
    }

    /**
     * A plain {@link Product}, with no availability.
     *
     * <p>Used by the list endpoint, where calling inventory once per row would
     * turn one page render into N downstream calls. Jackson omits the null via
     * {@code @JsonInclude(NON_NULL)}, so list responses carry no empty key
     * rather than an explicit {@code "availability": null}.
     */
    public static ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.id(),
                product.title(),
                product.description().orElse(null),
                product.tags(),
                null
        );
    }

    /**
     * A {@link ProductWithAvailability} — the product plus the
     * inventory-derived stock state — for the single-product read.
     *
     * <p>This is the overload that caused the inversion: its parameter is a
     * service-layer type, so having it on the DTO made the DTO depend upward.
     */
    public static ProductResponse toResponse(ProductWithAvailability enriched) {
        Product product = enriched.product();
        return new ProductResponse(
                product.id(),
                product.title(),
                product.description().orElse(null),
                product.tags(),
                enriched.availability()
        );
    }
}
