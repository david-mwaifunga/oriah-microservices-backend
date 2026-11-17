package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.catalog.exception.PromotionsUnavailableException;
import com.oriah.catalog.promotion.Discount;
import com.oriah.catalog.promotion.PromotionDefinition;
import com.oriah.catalog.repository.CategoryAncestry;
import com.oriah.catalog.service.PromotionPricing.Piece;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Works out the discount each piece gets at an instant: one question to promotion for the whole bag (never one per piece), the category tree
 * asked about only when a category promotion is in force, and no answer at all when there is nothing to price.
 */
class PromotionPricingTest {

    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private PromotionLookup lookup;
    private CategoryAncestry ancestry;
    private PromotionPricing pricing;

    @BeforeEach
    void setUp() {
        lookup = mock(PromotionLookup.class);
        ancestry = mock(CategoryAncestry.class);
        pricing = new PromotionPricing(lookup, ancestry);
    }

    private static PromotionDefinition promo(String scope, UUID target, String type, String amount) {
        return new PromotionDefinition(UUID.randomUUID(), scope, target, type, new BigDecimal(amount), java.time.LocalDate.parse("2026-11-20"),
                Instant.parse("2026-11-01T08:00:00Z"));
    }

    private static Piece piece(UUID product, String price) {
        return new Piece(UUID.randomUUID(), product, new BigDecimal(price), false);
    }

    @Test
    @DisplayName("a returned piece sold again at a lower price takes no promotion: shop-wide, on its category or on its own product, now or as of a past day")
    void aMarkdownTakesNoPromotion() {
        UUID product = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        when(lookup.inForce(any())).thenReturn(List.of(promo("SHOP", null, "PERCENTAGE", "10.00"), promo("PRODUCT", product, "FIXED", "50.00"),
                promo("CATEGORY", category, "PERCENTAGE", "20.00")));
        when(ancestry.categoriesWithAncestors(anyCollection())).thenReturn(Map.of(product, java.util.Set.of(category)));
        Piece ordinary = piece(product, "1000.00");
        Piece markedDown = new Piece(UUID.randomUUID(), product, new BigDecimal("700.00"), true);

        for (Instant at : new Instant[] {null, AT}) {
            Map<UUID, Discount> answer = pricing.discountsAt(List.of(ordinary, markedDown), at);

            assertThat(answer).containsOnlyKeys(ordinary.variantId());
            assertThat(answer.get(ordinary.variantId()).perPiece()).isEqualByComparingTo("200.00");
        }
    }

    @Test
    @DisplayName("a bag of marked-down pieces only is no question to promotion at all")
    void onlyMarkdownsAskNobody() {
        assertThat(pricing.discountsAt(List.of(new Piece(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("700.00"), true)), AT)).isEmpty();

        verify(lookup, never()).inForce(any());
    }

    @Test
    @DisplayName("nothing to price is no question to anyone: an empty bag never reaches promotion")
    void nothingToPrice() {
        assertThat(pricing.discountsAt(List.of(), AT)).isEmpty();

        verify(lookup, never()).inForce(any());
        verify(ancestry, never()).categoriesWithAncestors(anyCollection());
    }

    @Test
    @DisplayName("promotion is asked ONCE for the whole bag, at the instant given, however many pieces there are")
    void oneQuestionForTheWholeBag() {
        when(lookup.inForce(AT)).thenReturn(List.of(promo("SHOP", null, "PERCENTAGE", "10.00")));
        List<Piece> bag = List.of(piece(UUID.randomUUID(), "100.00"), piece(UUID.randomUUID(), "200.00"), piece(UUID.randomUUID(), "300.00"));

        Map<UUID, Discount> answer = pricing.discountsAt(bag, AT);

        verify(lookup, times(1)).inForce(AT);
        assertThat(answer).hasSize(3);
        assertThat(answer.get(bag.get(0).variantId()).perPiece()).isEqualByComparingTo("10.00");
        assertThat(answer.get(bag.get(2).variantId()).perPiece()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("with no promotion in force the answer is empty and the category tree is never read")
    void noPromotionNoCategoryQuery() {
        when(lookup.inForce(AT)).thenReturn(List.of());

        assertThat(pricing.discountsAt(List.of(piece(UUID.randomUUID(), "100.00")), AT)).isEmpty();

        verify(ancestry, never()).categoriesWithAncestors(anyCollection());
    }

    @Test
    @DisplayName("a shop-wide or product promotion needs no category tree either: only a category promotion reads it")
    void onlyACategoryPromotionReadsTheTree() {
        UUID product = UUID.randomUUID();
        when(lookup.inForce(AT)).thenReturn(List.of(promo("SHOP", null, "FIXED", "5.00"), promo("PRODUCT", product, "FIXED", "7.00")));

        pricing.discountsAt(List.of(piece(product, "100.00")), AT);

        verify(ancestry, never()).categoriesWithAncestors(anyCollection());
    }

    @Test
    @DisplayName("a category promotion reads the tree once for the distinct products in the bag, and covers a piece through an ancestor category")
    void categoryPromotion() {
        UUID jewellery = UUID.randomUUID();
        UUID rings = UUID.randomUUID();
        UUID ringProduct = UUID.randomUUID();
        UUID watchProduct = UUID.randomUUID();
        PromotionDefinition onJewellery = promo("CATEGORY", jewellery, "PERCENTAGE", "20.00");
        when(lookup.inForce(AT)).thenReturn(List.of(onJewellery));
        when(ancestry.categoriesWithAncestors(anyCollection())).thenReturn(Map.of(ringProduct, Set.of(rings, jewellery), watchProduct, Set.of()));
        Piece ringSmall = piece(ringProduct, "100.00");
        Piece ringBig = piece(ringProduct, "500.00");
        Piece watch = piece(watchProduct, "900.00");

        Map<UUID, Discount> answer = pricing.discountsAt(List.of(ringSmall, ringBig, watch), AT);

        verify(ancestry, times(1)).categoriesWithAncestors(Set.of(ringProduct, watchProduct));
        assertThat(answer.get(ringSmall.variantId()).perPiece()).isEqualByComparingTo("20.00");
        assertThat(answer.get(ringBig.variantId()).perPiece()).as("each piece at its own price").isEqualByComparingTo("100.00");
        assertThat(answer.get(ringBig.variantId()).promotionId()).isEqualTo(onJewellery.id());
        assertThat(answer).as("a watch is not under Jewellery").doesNotContainKey(watch.variantId());
    }

    @Test
    @DisplayName("a promotion that cannot be asked is a 503 and nothing is priced: the failure is never read as 'no promotion'")
    void anOutageIsNotSilence() {
        when(lookup.inForce(AT)).thenThrow(new PromotionsUnavailableException(new RuntimeException("down")));

        assertThatThrownBy(() -> pricing.discountsAt(List.of(piece(UUID.randomUUID(), "100.00")), AT)).isInstanceOf(PromotionsUnavailableException.class);
    }

    @Test
    @DisplayName("an instant of 'now' is passed on as null, so promotion's own clock answers")
    void nowIsNull() {
        when(lookup.inForce(null)).thenReturn(List.of());

        pricing.discountsAt(List.of(piece(UUID.randomUUID(), "100.00")), null);

        verify(lookup).inForce(null);
    }
}
