package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.oriah.catalog.client.InventoryClient;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.events.ProductCreatedEvent;
import com.oriah.catalog.events.ProductDeletedEvent;
import com.oriah.catalog.events.ProductUpdatedEvent;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level tests proving that {@link ProductService} publishes the right
 * domain event after each successful state change.
 *
 * <p>The service emits events via Spring's in-process
 * {@link ApplicationEventPublisher} (not directly to Kafka) so that:
 * <ul>
 *   <li>these tests don't need a Kafka container or a stream test binder,</li>
 *   <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} on the
 *       Kafka bridge guarantees we don't publish on rollback.</li>
 * </ul>
 *
 * <p>Repository, inventory client, and event publisher are plain Mockito
 * mocks — full Spring boot is not needed to verify "service called publisher
 * with this payload."
 */
class ProductServiceEventPublishingTest {

    private ProductRepository repository;
    private InventoryClient inventoryClient;
    private ApplicationEventPublisher eventPublisher;
    private ProductService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductRepository.class);
        inventoryClient = mock(InventoryClient.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        // CatalogProperties needs no mocking -- the default ctor leaves
        // lowStockThreshold at its initializer value (5), which these
        // unit tests don't read anyway (they verify event publishing,
        // not availability calculation).
        service = new ProductService(repository, inventoryClient, eventPublisher,
                new com.oriah.catalog.config.CatalogProperties());
    }

    @Test
    @DisplayName("create() publishes ProductCreatedEvent carrying the saved product's id/title/tags")
    void createPublishesProductCreatedEvent() {
        // 'save' returns the same instance with whatever id was assigned at construction.
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.create("Aurelia", "18k", List.of("rings", "gold"));

        ProductCreatedEvent expected = new ProductCreatedEvent(
                saved.id(),
                "Aurelia",
                List.of("rings", "gold")
        );
        verify(eventPublisher, times(1)).publishEvent(expected);
    }

    @Test
    @DisplayName("update() publishes ProductUpdatedEvent with the post-update snapshot")
    void updatePublishesProductUpdatedEvent() {
        UUID id = UUID.randomUUID();
        Product existing = new Product("Aurelia", "old", List.of("rings"));
        // Reflection-free way to use a known id: rely on the no-id constructor
        // and fetch the auto-assigned id via the entity's accessor — for this
        // assertion we use whatever id existing happens to have.
        when(repository.findById(existing.id())).thenReturn(Optional.of(existing));

        ProductUpdateRequest patch = new ProductUpdateRequest(
                "Aurelia Renamed",
                "new copy",
                List.of("rings", "gold")
        );
        service.update(existing.id(), patch);

        ProductUpdatedEvent expected = new ProductUpdatedEvent(
                existing.id(),
                "Aurelia Renamed",
                "new copy",
                List.of("rings", "gold")
        );
        verify(eventPublisher, times(1)).publishEvent(expected);
    }

    @Test
    @DisplayName("update() of an unknown id publishes nothing")
    void updateOfUnknownIdDoesNotPublish() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.update(id, new ProductUpdateRequest("X", null, null));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("deleteById() publishes ProductDeletedEvent when a product was actually removed")
    void deletePublishesProductDeletedEvent() {
        Product existing = new Product("Aurelia", null, List.of("rings"));
        when(repository.findById(existing.id())).thenReturn(Optional.of(existing));

        boolean removed = service.deleteById(existing.id());

        assertThat(removed).isTrue();
        verify(eventPublisher, times(1)).publishEvent(new ProductDeletedEvent(existing.id()));
    }

    @Test
    @DisplayName("deleteById() of an unknown id publishes nothing")
    void deleteOfUnknownIdDoesNotPublish() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.deleteById(id);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
