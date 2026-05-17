package com.oriah.catalog.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    @Test
    @DisplayName("a product requires a non-blank title")
    void requiresANonBlankTitle() {
        assertThatThrownBy(() -> new Product(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("a product remembers its title")
    void remembersItsTitle() {
        var product = new Product("Aurelia Solitaire Ring");

        assertThat(product.title()).isEqualTo("Aurelia Solitaire Ring");
    }

    @Test
    @DisplayName("a product can have a description")
    void canHaveADescription() {
        var product = new Product("Aurelia", "A single-stone solitaire in 18k recycled gold.");

        assertThat(product.description()).hasValue("A single-stone solitaire in 18k recycled gold.");
    }

    @Test
    @DisplayName("description is empty when not provided")
    void descriptionIsEmptyWhenNotProvided() {
        var product = new Product("Aurelia");

        assertThat(product.description()).isEmpty();
    }
}
