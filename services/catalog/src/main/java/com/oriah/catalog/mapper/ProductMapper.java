package com.oriah.catalog.mapper;

import java.util.List;

import com.oriah.catalog.dto.CategoryResponse;
import com.oriah.catalog.dto.ProductImageResponse;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.VariantResponse;
import com.oriah.catalog.model.Category;
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
                // availability stays null: one inventory call per row would
                // turn a single page render into N downstream calls.
                null,
                product.slug(),
                product.status().name(),
                product.productTypeCode(),
                product.kind().name(),
                // attributes, option axes and variants are detail-only -- a grid
                // renders none of them, and attributes alone can be a dozen
                // fields a card never shows.
                null,
                null,
                toCategories(product),
                product.primaryCategory().map(ProductMapper::toCategory).orElse(null),
                product.lowestActivePrice().orElse(null),
                product.highestActivePrice().orElse(null),
                product.currency().orElse(null),
                null,
                toImages(product)
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
                enriched.availability(),
                product.slug(),
                product.status().name(),
                product.productTypeCode(),
                product.kind().name(),
                product.attributes(),
                product.optionNames(),
                toCategories(product),
                product.primaryCategory().map(ProductMapper::toCategory).orElse(null),
                product.lowestActivePrice().orElse(null),
                product.highestActivePrice().orElse(null),
                product.currency().orElse(null),
                toVariants(product),
                toImages(product)
        );
    }

    /**
     * The full product for an ADMIN caller: variants and attributes included,
     * availability left out.
     *
     * <p>Sits between the two public mappings for a reason. The list mapping
     * omits variants because a grid does not render them; the detail mapping
     * includes them but also carries an inventory-derived availability, which
     * costs a downstream call. An admin write needs the former's cheapness and
     * the latter's completeness: after adding a variant or publishing, the
     * screen has to show the recomputed variant list, price range and
     * publishability immediately — and asking inventory for stock levels is
     * not part of that.
     *
     * <p>Without this, {@code addVariant} answered with the LIST shape, whose
     * {@code variants} field is null — so an endpoint documented as returning
     * the product's new state did not return the variant it had just created,
     * and a caller had to issue a second request to see its own write.
     */
    public static ProductResponse toAdminResponse(Product product) {
        return new ProductResponse(
                product.id(),
                product.title(),
                product.description().orElse(null),
                product.tags(),
                null,
                product.slug(),
                product.status().name(),
                product.productTypeCode(),
                product.kind().name(),
                product.attributes(),
                product.optionNames(),
                toCategories(product),
                product.primaryCategory().map(ProductMapper::toCategory).orElse(null),
                product.lowestActivePrice().orElse(null),
                product.highestActivePrice().orElse(null),
                product.currency().orElse(null),
                toVariants(product),
                toImages(product));
    }

    /**
     * Every variant, INCLUDING inactive ones.
     *
     * <p>Deliberate: a size that exists but is withdrawn should render as an
     * unavailable option, not vanish. A missing size reads to a customer as
     * "they never made it", and to an operator as a bug. The {@code active}
     * flag is what the selector greys out.
     *
     * <p>{@code availability} is left null here -- stock lives in
     * inventory-service, and resolving it per variant means a bulk lookup the
     * detail path does not yet make.
     */
    private static List<VariantResponse> toVariants(Product product) {
        return product.variants().stream()
                .map(v -> new VariantResponse(
                        v.id(),
                        v.sku(),
                        v.unitPrice(),
                        v.currency(),
                        v.options(),
                        v.isActive(),
                        List.of(),
                        null))
                .toList();
    }

    private static List<ProductImageResponse> toImages(Product product) {
        return product.images().stream()
                .map(i -> ProductImageResponse.of(
                        i.id(), i.altText().orElse(null), i.width(), i.height()))
                .toList();
    }

    /**
     * A product's categories WITHOUT their children populated. The navigation
     * tree is its own endpoint; nesting it here would ship the whole taxonomy
     * on every card in the grid.
     */
    private static List<CategoryResponse> toCategories(Product product) {
        return product.categories().stream()
                .map(ProductMapper::toCategory)
                .sorted(java.util.Comparator.comparing(CategoryResponse::slug))
                .toList();
    }

    static CategoryResponse toCategory(Category category) {
        return new CategoryResponse(
                category.id(),
                category.slug(),
                category.name(),
                category.description().orElse(null),
                category.position(),
                null);
    }
}
