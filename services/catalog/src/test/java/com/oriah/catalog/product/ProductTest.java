package com.oriah.catalog.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    @Test
    @DisplayName("a product requires a non-blank title")
    void requiresANonBlankTitle() {
        assertThatThrownBy(() -> new Product(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }
}
