package com.oriah.procurement.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.oriah.procurement.client.CatalogClient.VariantLookupBody;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;
import com.oriah.procurement.exception.CatalogUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asking the catalogue about variants. Catalog answers at most 100 at a time (its lookup refuses
 * more, with a 400), and the opening consignment may name up to 200 lines, so a longer list is
 * asked for in batches of 100 and the answers put together. A refusal there used to be reported as
 * "the catalogue could not be asked" (503), which sent the person to retry something that could
 * never work.
 */
class CatalogVariantsTest {

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static CatalogClient catalogKnowingEverything() {
        CatalogClient catalog = Mockito.mock(CatalogClient.class);
        when(catalog.lookup(any())).thenAnswer(call -> {
            VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream()
                    .map(id -> new Entry(id, true, UUID.randomUUID(), "P", "P - " + id, "SKU-" + id, "SELLABLE", "DRAFT",
                            true, false))
                    .toList());
        });
        return catalog;
    }

    @Test
    @DisplayName("up to 100 variants are asked about in ONE call")
    void oneCallForAHundred() {
        CatalogClient catalog = catalogKnowingEverything();

        Map<UUID, Entry> known = new CatalogVariants(catalog).lookup(ids(100));

        assertThat(known).hasSize(100);
        verify(catalog, times(1)).lookup(any());
    }

    @Test
    @DisplayName("more than 100 are asked in batches of 100, every one is answered, and no batch is over the limit")
    void batchesOfAHundred() {
        CatalogClient catalog = catalogKnowingEverything();
        List<UUID> asked = ids(250);

        Map<UUID, Entry> known = new CatalogVariants(catalog).lookup(asked);

        assertThat(known).hasSize(250).containsKeys(asked.toArray(UUID[]::new));
        ArgumentCaptor<VariantLookupBody> bodies = ArgumentCaptor.forClass(VariantLookupBody.class);
        verify(catalog, times(3)).lookup(bodies.capture());
        assertThat(bodies.getAllValues()).extracting(b -> b.variantIds().size()).containsExactly(100, 100, 50);
    }

    @Test
    @DisplayName("a repeated id is asked about once")
    void repeatsAreAskedOnce() {
        CatalogClient catalog = catalogKnowingEverything();
        UUID same = UUID.randomUUID();

        Map<UUID, Entry> known = new CatalogVariants(catalog).lookup(List.of(same, same, same));

        assertThat(known).hasSize(1);
        ArgumentCaptor<VariantLookupBody> body = ArgumentCaptor.forClass(VariantLookupBody.class);
        verify(catalog).lookup(body.capture());
        assertThat(body.getValue().variantIds()).containsExactly(same);
    }

    @Test
    @DisplayName("if any batch fails, nothing is answered: a half-known list is never used")
    void aFailedBatchFailsEverything() {
        CatalogClient catalog = Mockito.mock(CatalogClient.class);
        when(catalog.lookup(any()))
                .thenReturn(new VariantLookupResult(List.of()))
                .thenThrow(new RuntimeException("catalog is down"));

        assertThatThrownBy(() -> new CatalogVariants(catalog).lookup(ids(150)))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    @DisplayName("a reply with no results at all is a failure, not an empty answer")
    void aMissingReplyIsAFailure() {
        CatalogClient catalog = Mockito.mock(CatalogClient.class);
        when(catalog.lookup(any())).thenReturn(new VariantLookupResult(null));

        assertThatThrownBy(() -> new CatalogVariants(catalog).lookup(ids(3)))
                .isInstanceOf(CatalogUnavailableException.class);
    }
}
