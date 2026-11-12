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

    /**
     * What catalog says each variant is, by id. ONE call, however many ids (the lookup
     * takes up to 100 and a purchase order holds at most that many lines).
     */
    public Map<UUID, Entry> lookup(List<UUID> variantIds) {
        List<UUID> distinct = variantIds.stream().distinct().toList();
        try {
            var result = catalog.lookup(new VariantLookupBody(distinct));
            if (result == null || result.results() == null) {
                throw new IllegalStateException("catalog answered the lookup with no results");
            }
            Map<UUID, Entry> byId = new HashMap<>();
            for (Entry entry : result.results()) {
                byId.put(entry.variantId(), entry);
            }
            return byId;
        } catch (RuntimeException e) {
            throw new CatalogUnavailableException(e);
        }
    }
}
