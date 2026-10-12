package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.catalog.dto.VariantPricingResponse.Status;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.repository.ProductVariantRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The endpoint that makes the catalogue the pricing authority.
 *
 * <h2>What this replaces</h2>
 *
 * cart-service currently takes {@code unitPrice} from the client and stores it
 * without asking anyone. Proved against the running stack: adding an item at
 * {@code unitPrice: 0.01} produced a cart subtotal of 0.01 and a checkout
 * preview total of 5.01. This service is where the real number comes from, so
 * every assertion below is load-bearing for money.
 *
 * <h2>Why a status per variant instead of a 404</h2>
 *
 * A bag has to render a line that can no longer be bought, and the wording
 * depends on why. A withdrawn variant is "no longer available"; an id that
 * resolves to nothing is a line to discard. One 404 cannot say both, and
 * guessing wrong means telling a customer an item is unavailable when in fact
 * their bag is corrupt.
 *
 * <p>Mocked repository on purpose: the subject here is the status decision and
 * the price-withholding rules, not SQL. The query itself is exercised against
 * real Postgres in the repository tests. Plain Mockito mocks wired by hand,
 * matching ProductServiceEventPublishingTest -- full Spring is not needed to
 * check which status a variant resolves to.
 */
class VariantPricingServiceTest {

    private ProductVariantRepository variants;
    private DefaultVariantPricingService service;

    @BeforeEach
    void setUp() {
        variants = mock(ProductVariantRepository.class);
        service = new DefaultVariantPricingService(variants);
    }

    private static Product publishedRing() {
        var p = new Product("Aurelia Solitaire Ring", null, List.of());
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(new ProductVariant(p, "AUR-M", new BigDecimal("4500.00"), "ZMW",
                Map.of("Ring Size", "M")));
        p.publish();
        return p;
    }

    @Test
    @DisplayName("a live variant comes back PRICED, with the catalogue's price")
    void livePriced() {
        var product = publishedRing();
        var variant = product.variants().getFirst();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(variant));

        var entry = service.priceAll(List.of(variant.id())).results().getFirst();

        assertThat(entry.status()).isEqualTo(Status.PRICED);
        assertThat(entry.unitPrice()).isEqualByComparingTo("4500.00");
        assertThat(entry.currency()).isEqualTo("ZMW");
        assertThat(entry.productId()).isEqualTo(product.id());
        assertThat(entry.productTitle()).isEqualTo("Aurelia Solitaire Ring");
    }

    @Test
    @DisplayName("the variant title names the option, so a bag line reads like a product and not a SKU")
    void variantTitleIncludesOptions() {
        var product = publishedRing();
        var variant = product.variants().getFirst();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(variant));

        assertThat(service.priceAll(List.of(variant.id())).results().getFirst().variantTitle())
                .isEqualTo("Aurelia Solitaire Ring - Ring Size M");
    }

    @Test
    @DisplayName("a product that varies on nothing has no option suffix")
    void variantTitleOmitsEmptyOptions() {
        var p = new Product("Minimal Cuff", null, List.of());
        p.addVariant(new ProductVariant(p, "CUF-1", new BigDecimal("1200.00"), "ZMW", Map.of()));
        p.publish();
        var variant = p.variants().getFirst();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(variant));

        assertThat(service.priceAll(List.of(variant.id())).results().getFirst().variantTitle())
                .isEqualTo("Minimal Cuff");
    }

    @Test
    @DisplayName("an unknown id comes back UNKNOWN with no price, rather than being dropped")
    void unknownIdIsReported() {
        var missing = UUID.randomUUID();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of());

        var results = service.priceAll(List.of(missing)).results();

        // Dropping it would make the caller compare list lengths to work out
        // what happened, and a cart line would silently keep its stale price.
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().status()).isEqualTo(Status.UNKNOWN);
        assertThat(results.getFirst().variantId()).isEqualTo(missing);
        assertThat(results.getFirst().unitPrice()).isNull();
    }

    @Test
    @DisplayName("a DRAFT product's variant is UNPUBLISHED and its price is withheld")
    void draftProductIsUnpublished() {
        var p = new Product("Unreleased Ring", null, List.of());
        p.addVariant(new ProductVariant(p, "UNR-1", new BigDecimal("4500.00"), "ZMW", Map.of()));
        // Never published -- this is the state V3 left the whole existing
        // catalogue in.
        var variant = p.variants().getFirst();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(variant));

        var entry = service.priceAll(List.of(variant.id())).results().getFirst();

        assertThat(entry.status()).isEqualTo(Status.UNPUBLISHED);
        // The price is DELIBERATELY absent. A caller that forgot to check the
        // status would otherwise read a real number off an unpublished product
        // and charge for something not for sale.
        assertThat(entry.unitPrice()).isNull();
        assertThat(entry.currency()).isNull();
        // The product id and title are still given, so a bag can say WHICH
        // line is unavailable.
        assertThat(entry.productTitle()).isEqualTo("Unreleased Ring");
    }

    @Test
    @DisplayName("an ARCHIVED product's variant is UNPUBLISHED")
    void archivedProductIsUnpublished() {
        var p = publishedRing();
        p.archive();
        var variant = p.variants().getFirst();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(variant));

        assertThat(service.priceAll(List.of(variant.id())).results().getFirst().status())
                .isEqualTo(Status.UNPUBLISHED);
    }

    @Test
    @DisplayName("an inactive variant of a live product is UNPUBLISHED")
    void inactiveVariantIsUnpublished() {
        var p = publishedRing();
        p.declareOptionNames(List.of("Ring Size"));
        p.addVariant(new ProductVariant(p, "AUR-L", new BigDecimal("4700.00"), "ZMW",
                Map.of("Ring Size", "L")));
        var withdrawn = p.variants().getFirst();
        p.deactivateVariant(withdrawn.id());
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(withdrawn));

        var entry = service.priceAll(List.of(withdrawn.id())).results().getFirst();
        assertThat(entry.status()).isEqualTo(Status.UNPUBLISHED);
        assertThat(entry.unitPrice()).isNull();
    }

    @Test
    @DisplayName("a zero-priced variant is UNPUBLISHED, not free")
    void zeroPricedIsNotSellable() {
        // The single most important assertion in this class. V3 backfilled
        // every pre-existing variant at 0.00, so treating zero as a price
        // would make the entire migrated catalogue purchasable for nothing.
        var p = new Product("Unpriced Legacy Ring", null, List.of());
        p.addVariant(new ProductVariant(p, "LEG-1", new BigDecimal("0.00"), "ZMW", Map.of()));
        var variant = p.variants().getFirst();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(variant));

        var entry = service.priceAll(List.of(variant.id())).results().getFirst();

        assertThat(entry.status()).isEqualTo(Status.UNPUBLISHED);
        assertThat(entry.unitPrice()).isNull();
    }

    @Test
    @DisplayName("every requested id appears exactly once, in the order asked")
    void resultsMirrorTheRequest() {
        var product = publishedRing();
        var known = product.variants().getFirst();
        var missing = UUID.randomUUID();
        when(variants.findAllByIdInWithProduct(anyList())).thenReturn(List.of(known));

        var results = service.priceAll(List.of(missing, known.id(), missing)).results();

        // Order and multiplicity preserved so a caller can zip the response
        // against its own lines positionally instead of building a map.
        assertThat(results).extracting("variantId")
                .containsExactly(missing, known.id(), missing);
    }

    @Test
    @DisplayName("an empty request is answered with an empty result, not a database call")
    void emptyRequestShortCircuits() {
        assertThat(service.priceAll(List.of()).results()).isEmpty();
        // The assertion that matters: no query is issued for nothing to look
        // up. cart calls this on every bag read, and an empty bag is common.
        verify(variants, never()).findAllByIdInWithProduct(anyList());
    }
}
