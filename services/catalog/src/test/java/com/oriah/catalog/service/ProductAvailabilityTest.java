package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.client.InventoryClient;
import com.oriah.catalog.client.InventoryDto;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.dto.VariantResponse;
import com.oriah.catalog.model.Availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stock badge of a product page is worked out from inventory's answer PER VARIANT.
 *
 * <p>Inventory keys its stock on the variant, and (Phase 5) a stock row exists only once goods of that variant have
 * been received. So the badge must be asked of each variant (never of the product's own id, which is a variant's id
 * only for the products that predate variants), and "inventory has no row" is an answer, not a failure: nothing of
 * it is in stock.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ProductAvailabilityTest {

    private static final String STAFF = "user:catalog-test";

    @Autowired
    private ProductService products;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private InventoryClient inventory;

    private UUID productId;
    private UUID small;
    private UUID large;

    @BeforeEach
    void seedPublishedProductWithTwoVariants() {
        jdbc.update("DELETE FROM product_variant_images");
        jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM product_variants");
        jdbc.update("DELETE FROM product_categories");
        jdbc.update("DELETE FROM product_tags");
        jdbc.update("UPDATE products SET primary_category_id = NULL");
        jdbc.update("DELETE FROM products");
        Mockito.reset(inventory);

        productId = products.create("Aurelia Solitaire Ring", null, List.of("rings"), null, null, null, null, null,
                List.of("Ring Size"), STAFF).id();
        products.addVariant(productId, new VariantCreateRequest("AUR-S", new BigDecimal("4500.00"), "ZMW", Map.of("Ring Size", "S")), STAFF);
        var withBoth = products.addVariant(productId, new VariantCreateRequest("AUR-L", new BigDecimal("4600.00"), "ZMW", Map.of("Ring Size", "L")), STAFF)
                .orElseThrow();
        small = withBoth.variants().get(0).id();
        large = withBoth.variants().get(1).id();
        products.publish(productId, STAFF);
    }

    private static ResponseEntity<InventoryDto> stock(UUID variant, int available) {
        return ResponseEntity.ok(new InventoryDto(variant, available, 0, available));
    }

    private static ResponseEntity<InventoryDto> noStockRow() {
        return ResponseEntity.notFound().build();
    }

    private ProductResponse page() {
        return products.findByIdWithAvailability(productId).orElseThrow();
    }

    private static Map<UUID, String> badges(ProductResponse page) {
        Map<UUID, String> byVariant = new java.util.LinkedHashMap<>();
        for (VariantResponse v : page.variants()) {
            byVariant.put(v.id(), v.availability());
        }
        return byVariant;
    }

    @Test
    @DisplayName("each variant is asked for, by its own id: the product's id is never sent to inventory")
    void askedByVariantNotByProduct() {
        when(inventory.getByVariantId(small)).thenReturn(stock(small, 10));
        when(inventory.getByVariantId(large)).thenReturn(stock(large, 10));

        page();

        verify(inventory).getByVariantId(small);
        verify(inventory).getByVariantId(large);
        verify(inventory, never()).getByVariantId(productId);
    }

    @Test
    @DisplayName("no stock row at inventory means none in stock: OUT_OF_STOCK, not UNKNOWN")
    void noStockRowIsNoStock() {
        when(inventory.getByVariantId(any())).thenReturn(noStockRow());

        ProductResponse page = page();

        assertThat(page.availability()).isEqualTo(Availability.OUT_OF_STOCK);
        assertThat(badges(page)).containsEntry(small, "OUT_OF_STOCK").containsEntry(large, "OUT_OF_STOCK");
    }

    @Test
    @DisplayName("the product is as available as its variants together: 3 + 3 is IN_STOCK, each size alone is LOW_STOCK")
    void sumsAcrossVariants() {
        when(inventory.getByVariantId(small)).thenReturn(stock(small, 3));
        when(inventory.getByVariantId(large)).thenReturn(stock(large, 3));

        ProductResponse page = page();

        assertThat(page.availability()).isEqualTo(Availability.IN_STOCK);
        assertThat(badges(page)).containsEntry(small, "LOW_STOCK").containsEntry(large, "LOW_STOCK");
    }

    @Test
    @DisplayName("one size with stock is enough to buy: the other, with no row, reads OUT_OF_STOCK and the product LOW_STOCK")
    void oneSizeInStock() {
        when(inventory.getByVariantId(small)).thenReturn(noStockRow());
        when(inventory.getByVariantId(large)).thenReturn(stock(large, 3));

        ProductResponse page = page();

        assertThat(page.availability()).isEqualTo(Availability.LOW_STOCK);
        assertThat(badges(page)).containsEntry(small, "OUT_OF_STOCK").containsEntry(large, "LOW_STOCK");
    }

    @Test
    @DisplayName("a size sold out (a row at zero) is OUT_OF_STOCK like one never received")
    void soldOutIsOutOfStock() {
        when(inventory.getByVariantId(any())).thenAnswer(call -> stock(call.getArgument(0), 0));

        assertThat(page().availability()).isEqualTo(Availability.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("inventory failing (the breaker's fallback answers nothing) is UNKNOWN, and the other sizes are not asked")
    void inventoryDown() {
        when(inventory.getByVariantId(any())).thenReturn(null);

        ProductResponse page = page();

        assertThat(page.availability()).isEqualTo(Availability.UNKNOWN);
        assertThat(badges(page)).containsEntry(small, "UNKNOWN").containsEntry(large, "UNKNOWN");
        verify(inventory, times(1)).getByVariantId(any());
    }

    @Test
    @DisplayName("what is known stands when a later size cannot be asked: stock on the first, the second UNKNOWN, the product IN_STOCK")
    void aLaterFailureDoesNotHideStockAlreadyFound() {
        when(inventory.getByVariantId(small)).thenReturn(stock(small, 9));
        when(inventory.getByVariantId(large)).thenReturn(null);

        ProductResponse page = page();

        assertThat(page.availability()).isEqualTo(Availability.IN_STOCK);
        assertThat(badges(page)).containsEntry(small, "IN_STOCK").containsEntry(large, "UNKNOWN");
    }
}
