package com.oriah.procurement.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.oriah.procurement.client.CatalogClient.VariantLookupBody;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;
import com.oriah.procurement.exception.CatalogUnavailableException;

/**
 * Catalog's answers about variants, with every failure turned into one: "catalog could not
 * be asked" ({@link CatalogUnavailableException}, a 503).
 *
 * <p>A refused call (an expired token, a missing role), a connection that fails and a
 * timeout all come out the same way, because the caller can do the same about each: nothing
 * was saved, try again. No answer is ever invented.
 */
@Component
public class CatalogVariants {

    private final CatalogClient catalog;

    public CatalogVariants(CatalogClient catalog) {
        this.catalog = catalog;
    }

    /** The most variants catalog's lookup answers in one call: it refuses a longer list with a 400. */
    public static final int MAX_PER_CALL = 100;

    /**
     * What catalog says each variant is, by id. One call for up to {@value #MAX_PER_CALL} ids (a
     * purchase order holds at most that many lines); more (the opening stock may name 200) are asked
     * in batches of {@value #MAX_PER_CALL} and the answers put together. If any batch fails, nothing
     * is answered: a half-known list is never used.
     */
    public Map<UUID, Entry> lookup(List<UUID> variantIds) {
        List<UUID> distinct = variantIds.stream().distinct().toList();
        Map<UUID, Entry> byId = new HashMap<>();
        try {
            for (int from = 0; from < distinct.size(); from += MAX_PER_CALL) {
                List<UUID> batch = distinct.subList(from, Math.min(from + MAX_PER_CALL, distinct.size()));
                var result = catalog.lookup(new VariantLookupBody(List.copyOf(batch)));
                if (result == null || result.results() == null) {
                    throw new IllegalStateException("catalog answered the lookup with no results");
                }
                for (Entry entry : result.results()) {
                    byId.put(entry.variantId(), entry);
                }
            }
            return byId;
        } catch (RuntimeException e) {
            throw new CatalogUnavailableException(e);
        }
    }
}
