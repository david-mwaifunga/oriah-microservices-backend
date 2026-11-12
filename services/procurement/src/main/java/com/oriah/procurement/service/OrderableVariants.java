package com.oriah.procurement.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.oriah.observability.OriahExceptionHandlerSupport.FieldErrorView;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;
import com.oriah.procurement.client.CatalogVariants;
import com.oriah.procurement.exception.InvalidFieldsException;

/**
 * Which variants may be put on a purchase order or typed into the opening stock, and what the
 * catalogue calls them.
 *
 * <p>Eligibility (the variant exists, is switched on, and belongs to a product that is not
 * deleted or archived) is judged by asking the catalogue ONCE for all of them. Every problem is
 * reported together, each against its own line as {@code lines[i].variantId}, so the person can
 * fix a whole form in one go. A DRAFT product and a packaging entry are fine: goods are bought
 * before they are priced, and packaging is never published.
 *
 * <p>If the catalogue cannot be asked this throws a 503 and nothing is guessed.
 */
@Component
public class OrderableVariants {

    private final CatalogVariants catalog;

    public OrderableVariants(CatalogVariants catalog) {
        this.catalog = catalog;
    }

    /** What the catalogue calls a variant that may be ordered, as it was when it was asked. */
    public record Orderable(UUID variantId, String sku, String productTitle, String variantTitle, String kind) {
    }

    /**
     * @param variantIds one per line, in line order (repeats are allowed here: a repeat is the
     *                   caller's own rule to refuse)
     * @return one {@link Orderable} per id, in the same order
     * @throws InvalidFieldsException (400) naming each line whose variant cannot be used
     */
    public List<Orderable> resolve(List<UUID> variantIds) {
        Map<UUID, Entry> known = catalog.lookup(variantIds);

        List<FieldErrorView> problems = new ArrayList<>();
        List<Orderable> resolved = new ArrayList<>(variantIds.size());
        for (int i = 0; i < variantIds.size(); i++) {
            Entry entry = known.get(variantIds.get(i));
            String field = "lines[" + i + "].variantId";
            if (entry == null || !entry.found()) {
                problems.add(new FieldErrorView(field, "no such variant in the catalogue"));
            } else if (Boolean.TRUE.equals(entry.productDeleted())) {
                problems.add(new FieldErrorView(field, "belongs to a deleted product"));
            } else if ("ARCHIVED".equals(entry.productStatus())) {
                problems.add(new FieldErrorView(field, "belongs to an archived product"));
            } else if (!Boolean.TRUE.equals(entry.active())) {
                problems.add(new FieldErrorView(field, "is switched off in the catalogue"));
            } else {
                resolved.add(new Orderable(variantIds.get(i), entry.sku(), entry.productTitle(), entry.variantTitle(),
                        entry.kind()));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidFieldsException(problems);
        }
        return resolved;
    }
}
