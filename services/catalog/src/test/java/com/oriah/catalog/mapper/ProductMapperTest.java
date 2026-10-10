package com.oriah.catalog.mapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductWithAvailability;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductMapper} — the mapping itself, and the dependency direction it
 * exists to fix.
 *
 * <p>Two different kinds of assertion here on purpose. The mapping tests are
 * <strong>characterization</strong>: the move out of {@code ProductResponse}
 * was supposed to change no behaviour, and these pin the exact output so that
 * claim is checked rather than asserted. The last test is
 * <strong>architectural</strong>, and it is the one with teeth — it fails if
 * anyone puts a service-layer type back on the DTO.
 */
class ProductMapperTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static Product aurelia() {
        return new Product(ID, "Aurelia Solitaire Ring",
                "A single-stone solitaire in 18k recycled gold.",
                List.of("rings", "engagement"));
    }

    @Test
    @DisplayName("a plain product maps with availability left null")
    void plainProductHasNoAvailability() {
        ProductResponse r = ProductMapper.toResponse(aurelia());

        assertThat(r.id()).isEqualTo(ID);
        assertThat(r.title()).isEqualTo("Aurelia Solitaire Ring");
        assertThat(r.description()).isEqualTo("A single-stone solitaire in 18k recycled gold.");
        assertThat(r.tags()).containsExactly("rings", "engagement");
        assertThat(r.availability())
                .as("the list endpoint deliberately omits availability -- one inventory "
                        + "call per row would turn a page render into N downstream calls, "
                        + "and @JsonInclude(NON_NULL) drops the key entirely")
                .isNull();
    }

    @Test
    @DisplayName("an enriched product carries the inventory-derived availability")
    void enrichedProductCarriesAvailability() {
        ProductResponse r = ProductMapper.toResponse(
                new ProductWithAvailability(aurelia(), Availability.LOW_STOCK));

        assertThat(r.id()).isEqualTo(ID);
        assertThat(r.availability()).isEqualTo(Availability.LOW_STOCK);
    }

    @Test
    @DisplayName("an absent description becomes null, not an empty Optional")
    void absentDescriptionBecomesNull() {
        // Product.description() is an Optional<String>; the wire field is
        // nullable. This is one of the two reasons the mapping is hand-written
        // rather than generated -- a generator would need an override here and
        // another for availability, which is more configuration than the eight
        // lines it would replace.
        ProductResponse r = ProductMapper.toResponse(
                new Product(ID, "Lume Pendant", null, List.of("necklaces")));

        assertThat(r.description()).isNull();
    }

    @Test
    @DisplayName("the DTO does not depend on the service layer")
    void theDtoDoesNotDependOnTheServiceLayer() {
        // The defect this mapper exists to fix, and the only one of its kind
        // across the 12 services.
        //
        // ProductResponse used to carry `from(ProductWithAvailability)` as a
        // static factory, so com.oriah.catalog.dto imported
        // com.oriah.catalog.service: the wire format depended on the
        // orchestration layer. A DTO is the boundary -- it should depend on
        // nothing above it, or the boundary is not one.
        //
        // Asserted reflectively rather than by reading imports, because an
        // import is only evidence; a signature is the actual coupling.
        for (Method m : ProductResponse.class.getDeclaredMethods()) {
            for (Class<?> param : m.getParameterTypes()) {
                assertThat(param.getPackageName())
                        .as("ProductResponse.%s takes %s, which puts a service-layer type "
                                        + "in the DTO's signature. Map it in ProductMapper instead.",
                                m.getName(), param.getSimpleName())
                        .doesNotStartWith("com.oriah.catalog.service");
            }
            assertThat(m.getReturnType().getPackageName())
                    .as("ProductResponse.%s returns a service-layer type", m.getName())
                    .doesNotStartWith("com.oriah.catalog.service");
        }
    }
}
