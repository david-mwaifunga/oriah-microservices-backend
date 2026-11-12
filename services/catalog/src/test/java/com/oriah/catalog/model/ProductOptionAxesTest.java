package com.oriah.catalog.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The axes a product's variants vary on, as the API now lets an operator set
 * them. Whatever a client sends, the product answers with a clear refusal
 * (IllegalArgumentException, which the error contract renders as 400) and is
 * left exactly as it was: never a NullPointerException, which would be a 500.
 */
class ProductOptionAxesTest {

    @Test
    @DisplayName("an axis list with a null in it is refused as a client error, not a crash")
    void nullEntryIsRefused() {
        Product ring = new Product("Ring");

        assertThatThrownBy(() -> ring.declareOptionNames(Arrays.asList("Ring Size", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("option name");
        assertThat(ring.optionNames()).isEmpty();
    }

    @Test
    @DisplayName("a blank axis name is refused")
    void blankEntryIsRefused() {
        Product ring = new Product("Ring");

        assertThatThrownBy(() -> ring.declareOptionNames(List.of("  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ring.optionNames()).isEmpty();
    }

    @Test
    @DisplayName("an axis named twice, after trimming, is refused and nothing is changed")
    void duplicateAfterTrimIsRefused() {
        Product ring = new Product("Ring");

        assertThatThrownBy(() -> ring.declareOptionNames(List.of("Size", "Size ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThat(ring.optionNames()).isEmpty();
    }

    @Test
    @DisplayName("a null list means no axes, and a good list is kept in the order given")
    void nullListAndGoodListAreAccepted() {
        Product watch = new Product("Watch");

        watch.declareOptionNames(new ArrayList<>(List.of("Strap", "Case Size")));
        assertThat(watch.optionNames()).containsExactly("Strap", "Case Size");

        watch.declareOptionNames(null);
        assertThat(watch.optionNames()).isEmpty();
    }
}
