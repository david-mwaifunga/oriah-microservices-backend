package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The variant defends the ngwee rule itself, whoever calls it -- not only the
 * HTTP edge (MVP_PLAN Phase 0, item 7).
 */
class VariantPriceInNgweeTest {

    private final Product product = new Product(UUID.randomUUID(), "Aurelia 18k", "A ring.", List.of());

    @Test
    @DisplayName("a variant cannot be created at a tenth of a ngwee")
    void creationRefusesFractionsOfANgwee() {
        assertThatThrownBy(() -> new ProductVariant(product, "AUR-1", new BigDecimal("4500.005"), "ZMW", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4500.005");
    }

    @Test
    @DisplayName("a variant cannot be repriced to a tenth of a ngwee, and keeps its price")
    void repriceRefusesFractionsOfANgwee() {
        ProductVariant variant = new ProductVariant(product, "AUR-1", new BigDecimal("4500.00"), "ZMW", Map.of());

        assertThatThrownBy(() -> variant.reprice(new BigDecimal("4600.001"), "ZMW"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4600.001");
        assertThat(variant.unitPrice()).isEqualByComparingTo("4500.00");
    }
}
