package com.oriah.catalog.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.dto.VariantLookupResponse;
import com.oriah.catalog.dto.VariantLookupResponse.Entry;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.repository.ProductVariantRepository;

/**
 * Names variants for procurement.
 *
 * <p>A purchase order line says which variant is being bought, and procurement
 * must not accept an id that is no variant (a mistyped id would be ordered, paid
 * for and received, and the cost lots would attach to nothing). Procurement owns no
 * copy of the catalogue, so it asks.
 *
 * <p>Deleted products are NOT hidden: the answer says {@code productDeleted}, and
 * the caller decides. Hiding them would make "deleted" and "never existed" look
 * the same, and the caller needs to tell its operator which it is.
 */
@Service
public class VariantLookupService {

    private final ProductVariantRepository variants;

    public VariantLookupService(ProductVariantRepository variants) {
        this.variants = variants;
    }

    /** One entry per requested id, in request order, duplicates included. */
    @Transactional(readOnly = true)
    public VariantLookupResponse lookup(List<UUID> variantIds) {
        List<UUID> distinct = variantIds.stream().distinct().toList();
        Map<UUID, ProductVariant> found = variants.findAllByIdInWithProduct(distinct).stream()
                .collect(Collectors.toMap(ProductVariant::id, Function.identity()));
        return new VariantLookupResponse(variantIds.stream()
                .map(id -> found.containsKey(id) ? entryOf(found.get(id)) : Entry.notFound(id))
                .toList());
    }

    private static Entry entryOf(ProductVariant variant) {
        Product product = variant.product();
        return new Entry(
                variant.id(),
                true,
                product.id(),
                product.title(),
                DefaultVariantPricingService.variantTitle(product, variant),
                variant.sku(),
                product.kind().name(),
                product.status().name(),
                variant.isActive(),
                product.isDeleted());
    }
}
