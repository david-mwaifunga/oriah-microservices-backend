package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.client.InventoryClient;
import com.oriah.catalog.client.InventoryDto;
import com.oriah.catalog.config.CatalogProperties;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.events.ProductCreatedEvent;
import com.oriah.catalog.events.ProductDeletedEvent;
import com.oriah.catalog.events.ProductUpdatedEvent;
import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.repository.ProductRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Transactional business operations on the {@link Product} aggregate.
 *
 * <p>Public methods are the unit of business-meaningful work — they wrap a
 * single transaction and emit the audit-level log line for the operation.
 * Calling code (controllers, future event listeners) should always go
 * through here rather than touch {@link ProductRepository} directly so the
 * transactional boundary and the log trail are guaranteed.
 *
 * <h2>Logging</h2>
 * <ul>
 *   <li><b>INFO</b> on every state-changing operation (create / update /
 *       delete) so the application log is sufficient for "who changed what
 *       when" reconstruction without needing the DB audit table.</li>
 *   <li><b>DEBUG</b> on read paths — these run on every page render in
 *       production, so info-level logging would be too noisy.</li>
 * </ul>
 */
@Service
@Slf4j
public class ProductService {

    private final ProductRepository repository;
    private final InventoryClient inventoryClient;
    private final ApplicationEventPublisher eventPublisher;
    private final CatalogProperties props;

    public ProductService(
            ProductRepository repository,
            InventoryClient inventoryClient,
            ApplicationEventPublisher eventPublisher,
            CatalogProperties props) {
        this.repository = repository;
        this.inventoryClient = inventoryClient;
        this.eventPublisher = eventPublisher;
        this.props = props;
    }

    /**
     * Find a product by its id. Returns {@link Optional#empty()} when the
     * id is unknown; the controller maps that to a 404.
     */
    @Transactional(readOnly = true)
    public Optional<Product> findById(UUID id) {
        log.debug("findById id={}", id);
        return repository.findById(id);
    }

    /**
     * Find a product and enrich it with stock availability looked up from
     * the inventory service.
     *
     * <p>The inventory call goes through the Resilience4j circuit breaker
     * wrapping {@link InventoryClient}. If inventory is down or slow, the
     * fallback returns {@code null} and we surface
     * {@link Availability#UNKNOWN} rather than failing — catalog stays up
     * even when inventory doesn't.
     *
     * <p>Catalog doesn't have a {@code Variant} entity yet, so we use
     * the product's id as the variant id when calling inventory. When
     * variants land (future iteration), this becomes a per-variant
     * lookup instead.
     */
    @Transactional(readOnly = true)
    public Optional<ProductWithAvailability> findByIdWithAvailability(UUID id) {
        log.debug("findByIdWithAvailability id={}", id);
        return repository.findById(id)
                .map(product -> new ProductWithAvailability(product, lookupAvailability(product.id())));
    }

    /**
     * Translates the inventory service's numeric response into the
     * domain {@link Availability} enum the wire format exposes.
     */
    private Availability lookupAvailability(UUID variantId) {
        InventoryDto inventory = inventoryClient.getByVariantId(variantId);
        if (inventory == null) {
            return Availability.UNKNOWN;
        }
        if (inventory.available() <= 0) {
            return Availability.OUT_OF_STOCK;
        }
        // Threshold lives in config-repo (oriah.catalog.low-stock-threshold)
        // and is rebound in place on /actuator/refresh (broadcast by
        // Spring Cloud Bus on /actuator/busrefresh from the configserver),
        // so this read returns the latest value without a service restart.
        if (inventory.available() < props.getLowStockThreshold()) {
            return Availability.LOW_STOCK;
        }
        return Availability.IN_STOCK;
    }

    /**
     * Returns a page of products, optionally filtered by a single tag.
     * Null {@code tag} means "no filter."
     */
    @Transactional(readOnly = true)
    public Page<Product> findAll(String tag, Pageable pageable) {
        log.debug("findAll tag={} page={} size={}", tag, pageable.getPageNumber(), pageable.getPageSize());
        return repository.findOptionallyFilteredByTag(tag, pageable);
    }

    /**
     * Creates and persists a new product. Title is required and validated by
     * the {@link Product} domain rule; description and tags are optional.
     * The repository assigns no id of its own — {@link Product} generates a
     * UUID at construction.
     */
    @Transactional
    public Product create(String title, String description, List<String> tags) {
        // Tag-count logging is friendlier than printing the full list: keeps
        // the line scannable and avoids leaking large payloads at INFO.
        int tagCount = tags == null ? 0 : tags.size();
        Product product = new Product(title, description, tags);
        Product saved = repository.save(product);
        log.info("Created product id={} title=\"{}\" tagCount={}", saved.id(), saved.title(), tagCount);
        // Publish via Spring's in-process event bus. The KafkaProductEventBridge
        // listens with @TransactionalEventListener(phase = AFTER_COMMIT) and
        // only forwards to Kafka after this @Transactional method commits.
        // Tag list is copied via List.copyOf to decouple the event from the
        // entity's mutable internal storage.
        eventPublisher.publishEvent(new ProductCreatedEvent(
                saved.id(),
                saved.title(),
                saved.tags() == null ? List.of() : List.copyOf(saved.tags())
        ));
        return saved;
    }

    /**
     * Applies a partial update to an existing product. Returns
     * {@link Optional#empty()} when no product exists with the given id.
     *
     * <p>Null fields in {@code request} are treated as "do not touch."
     * Non-null fields replace the corresponding entity field. Changes are
     * persisted automatically by JPA dirty checking because this method
     * runs inside a {@code @Transactional} boundary and the entity is in
     * managed state.
     */
    @Transactional
    public Optional<Product> update(UUID id, ProductUpdateRequest request) {
        Optional<Product> result = repository.findById(id).map(product -> {
            // Track which fields actually changed so the log line is useful
            // when investigating "why did this product's title change?"
            StringBuilder changedFields = new StringBuilder();
            if (request.title() != null) {
                product.updateTitle(request.title());
                changedFields.append("title ");
            }
            if (request.description() != null) {
                product.updateDescription(request.description());
                changedFields.append("description ");
            }
            if (request.tags() != null) {
                product.updateTags(request.tags());
                changedFields.append("tags ");
            }
            log.info("Updated product id={} changedFields=[{}]", product.id(), changedFields.toString().trim());
            // Snapshot the post-update state into the event. We send the
            // current values for *all* fields (not just changed ones) so
            // consumers don't need a prior read to reconstruct state.
            eventPublisher.publishEvent(new ProductUpdatedEvent(
                    product.id(),
                    product.title(),
                    product.description().orElse(null),
                    product.tags() == null ? List.of() : List.copyOf(product.tags())
            ));
            return product;
        });

        if (result.isEmpty()) {
            log.debug("Update skipped, product not found id={}", id);
        }
        return result;
    }

    /**
     * Deletes the product with the given id. Returns true if a product was
     * found and removed, false if no product had that id. The two-step
     * lookup-then-delete lets the controller return 204 vs 404 unambiguously
     * — {@code repository.deleteById} is silent when the id is missing.
     */
    @Transactional
    public boolean deleteById(UUID id) {
        boolean removed = repository.findById(id)
                .map(product -> {
                    repository.delete(product);
                    return true;
                })
                .orElse(false);

        if (removed) {
            log.info("Deleted product id={}", id);
            eventPublisher.publishEvent(new ProductDeletedEvent(id));
        } else {
            log.debug("Delete skipped, product not found id={}", id);
        }
        return removed;
    }
}
