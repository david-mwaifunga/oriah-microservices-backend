package com.oriah.catalog.product;

import java.util.List;

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

    @Test
    @DisplayName("a product can be tagged")
    void canBeTagged() {
        var product = new Product("Aurelia", null, List.of("solitaire", "engagement", "18k"));

        assertThat(product.tags()).containsExactly("solitaire", "engagement", "18k");
    }

    @Test
    @DisplayName("tags are empty when not provided")
    void tagsAreEmptyWhenNotProvided() {
        var product = new Product("Aurelia");

        assertThat(product.tags()).isEmpty();
    }

    @Test
    @DisplayName("tags returned to callers cannot be mutated")
    void tagsAreImmutable() {
        var product = new Product("Aurelia", null, List.of("solitaire"));

        assertThatThrownBy(() -> product.tags().add("ring"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
