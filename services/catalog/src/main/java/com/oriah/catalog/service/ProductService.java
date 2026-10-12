package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.client.InventoryClient;
import com.oriah.catalog.client.InventoryDto;
import com.oriah.catalog.config.CatalogProperties;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.events.ProductCreatedEvent;
import com.oriah.catalog.events.ProductDeletedEvent;
import com.oriah.catalog.events.ProductUpdatedEvent;
import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductSort;
import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.model.Slug;
import com.oriah.catalog.model.StorefrontFilter;
import com.oriah.catalog.mapper.ProductMapper;
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
    private final CategoryService categoryService;
    private final ProductImageService imageService;

    /**
     * How many readable discriminators to try before falling back to the id
     * suffix. Bounded so a pathological title cannot turn one create into an
     * unbounded number of queries.
     */
    private static final int MAX_SLUG_ATTEMPTS = 25;

    public ProductService(
            ProductRepository repository,
            InventoryClient inventoryClient,
            ApplicationEventPublisher eventPublisher,
            CatalogProperties props,
            CategoryService categoryService,
            ProductImageService imageService) {
        this.repository = repository;
        this.inventoryClient = inventoryClient;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.categoryService = categoryService;
        this.imageService = imageService;
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
    public Optional<ProductResponse> findByIdWithAvailability(UUID id) {
        log.debug("findByIdWithAvailability id={}", id);
        return repository.findById(id)
                // PUBLIC read: an unpublished product is invisible here, not
                // merely absent from the list. Excluding DRAFT from the list
                // while serving it by id left every unpublished product page
                // live to anyone holding the URL -- and ids are not secret;
                // they appear in Location headers, admin screens and logs.
                //
                // Empty (so the controller answers 404) rather than 403: a 403
                // would confirm that the id exists, which is exactly what an
                // unpublished product should not confirm.
                .filter(product -> product.status() == ProductStatus.ACTIVE)
                // MAPPED HERE, inside the transaction, and that is the whole
                // point of returning a DTO rather than the entity. The wire
                // format now reads variants, images and categories, all of
                // which are lazy; handing the entity back and letting the
                // controller map it threw LazyInitializationException on
                // Product.categories and answered 500. That is the same defect
                // this codebase already fixed once in CustomerService, so the
                // fix is the same shape: the transaction that loaded the
                // entity is the transaction that reads it.
                .map(product -> ProductMapper.toResponse(
                        new ProductWithAvailability(product, lookupAvailability(product.id()))));
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
     * The filtered, sorted storefront list.
     *
     * <p>Two queries, assembled here: the repository resolves which product
     * ids match and in what order, then loads those products with their
     * collections. See {@code ProductStorefrontQueriesImpl} for why that is not
     * one query with fetch joins.
     *
     * <p>Mapped inside this transaction, like every other read path in this
     * service -- the wire format reads three lazy collections.
     *
     * @param categorySlug resolved to the category AND its descendants, so
     *                     filtering by "Jewellery" returns rings too
     * @throws IllegalArgumentException for a category slug that does not
     *         exist, which the shared advice renders as a 400. Deliberately
     *         NOT an empty result: resolving an unknown slug yields no
     *         descendant ids, and a filter that matches nothing is
     *         indistinguishable from a category that is simply empty. This
     *         differs from the {@code tag} parameter, where an unknown tag
     *         legitimately answers 200 with an empty page -- a tag is a
     *         free-form label whose absence says nothing, whereas a category
     *         is a closed set and a slug outside it is a client error worth
     *         naming.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> findStorefront(
            String categorySlug, String typeCode, String tag,
            java.math.BigDecimal minPrice, java.math.BigDecimal maxPrice,
            ProductSort sort, Pageable pageable) {

        StorefrontFilter filter = StorefrontFilter.unfiltered()
                .withType(typeCode)
                .withTag(tag)
                .withPriceRange(minPrice, maxPrice);

        if (categorySlug != null && !categorySlug.isBlank()) {
            List<java.util.UUID> ids = categoryService.descendantIdsOf(categorySlug)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown category: " + categorySlug));
            filter = filter.withCategories(ids);
        }

        ProductSort effectiveSort = sort == null ? ProductSort.NEWEST : sort;
        List<java.util.UUID> ids = repository.findStorefrontIds(
                filter, effectiveSort, pageable.getPageNumber(), pageable.getPageSize());
        long total = repository.countStorefront(filter);

        List<ProductResponse> content = repository.findAllWithDetailByIdIn(ids).stream()
                .map(ProductMapper::toResponse)
                .toList();

        log.debug("findStorefront category={} type={} tag={} sort={} -> {} of {}",
                categorySlug, typeCode, tag, effectiveSort, content.size(), total);
        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }


    /**
     * The same product, for an operator, with no status filter.
     *
     * <p>Separate from the public read because the two have opposite jobs:
     * the storefront must not show a draft, and the admin must be able to work
     * on one. Collapsing them into one endpoint with a role check would mean a
     * single code path whose visibility depends on who is asking, which is
     * exactly the kind of conditional that leaks.
     */
    @Transactional(readOnly = true)
    public Optional<ProductResponse> findByIdForAdmin(UUID id) {
        log.debug("findByIdForAdmin id={}", id);
        return repository.findById(id)
                .map(product -> ProductMapper.toResponse(
                        new ProductWithAvailability(product, lookupAvailability(product.id()))));
    }

    /**
     * Adds a purchasable configuration to a product.
     *
     * <p>The product owns the invariants, not the variant: the option key set
     * must match the product's declared axes, the combination must not already
     * exist, and the currency must match its siblings. A variant cannot check
     * any of those, because it cannot see the others.
     *
     * @throws IllegalArgumentException on any of those violations, or an
     *         unknown product id; rendered as a 400 by the shared advice
     */
    @Transactional
    public Optional<ProductResponse> addVariant(UUID productId, VariantCreateRequest request) {
        return repository.findById(productId).map(product -> {
            ProductVariant variant = new ProductVariant(
                    product,
                    request.sku(),
                    request.unitPrice(),
                    request.currency(),
                    request.options());
            product.addVariant(variant);
            log.info("Added variant sku=\"{}\" to product id={} price={} {}",
                    variant.sku(), productId, variant.unitPrice(), variant.currency());
            return ProductMapper.toResponse(product);
        });
    }

    /**
     * Publishes a product to the storefront.
     *
     * <p>Refused unless the product has at least one active variant priced
     * above zero. That single rule is what makes the variants migration safe:
     * it backfilled every pre-existing product with a 0.00 variant, so without
     * it, publishing would have put the entire existing catalogue on sale for
     * nothing.
     *
     * @throws IllegalStateException when the product is not publishable, which
     *         the shared advice renders as a 409 -- a conflict with the
     *         product's current state rather than a malformed request
     */
    @Transactional
    public Optional<ProductResponse> publish(UUID productId) {
        return repository.findById(productId).map(product -> {
            product.publish();
            log.info("Published product id={} slug=\"{}\"", productId, product.slug());
            return ProductMapper.toResponse(product);
        });
    }

    /**
     * Creates and persists a new product. Title is required and validated by
     * the {@link Product} domain rule; description and tags are optional.
     * The repository assigns no id of its own — {@link Product} generates a
     * UUID at construction.
     */
    @Transactional
    public ProductResponse create(String title, String description, List<String> tags) {
        // Tag-count logging is friendlier than printing the full list: keeps
        // the line scannable and avoids leaking large payloads at INFO.
        int tagCount = tags == null ? 0 : tags.size();
        Product product = new Product(title, description, tags);
        product.assignSlug(freeSlugFor(product));
        Product saved = saveResolvingSlugCollision(product);
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
        // Mapped inside the transaction like every other read path here. A
        // freshly constructed product would in fact survive mapping outside
        // it -- its collections are real collections, not Hibernate proxies --
        // which is exactly why doing it consistently matters: create would
        // keep working while update failed, and the difference is invisible
        // at the call site.
        return ProductMapper.toResponse(saved);
    }

    /**
     * The first unused slug for this product.
     *
     * <h2>Why this exists at all</h2>
     *
     * {@code products.slug} is {@code NOT NULL UNIQUE} and the constructor
     * derives it from the title, so two products whose titles reduce to the
     * same slug violate the constraint. That is not an edge case: "Aurelia
     * Ring", "Aurelia-Ring" and "AURELIA RING" all reduce to
     * {@code aurelia-ring}, every title made only of punctuation reduces to
     * {@code product}, and a new title can collide with one the V3 migration
     * backfilled. Before this method, the second create answered
     * <strong>500</strong>, because nothing in the platform handled
     * {@code DataIntegrityViolationException}.
     *
     * <p>A numeric discriminator is tried first because {@code aurelia-ring-2}
     * is a URL someone can read, and the attempt is bounded -- past that, the
     * id suffix (the same shape V3 used) is guaranteed free, since ids are
     * unique.
     */
    private String freeSlugFor(Product product) {
        String base = Slug.from(product.title());
        if (!repository.existsBySlug(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_SLUG_ATTEMPTS; suffix++) {
            String candidate = base + "-" + suffix;
            if (!repository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        return idSuffixedSlug(base, product.id());
    }

    /** Collision-proof by construction: ids are unique. */
    private static String idSuffixedSlug(String base, UUID id) {
        return base + "-" + id.toString().replace("-", "").substring(0, 8);
    }

    /**
     * Saves, and survives losing a race for the slug.
     *
     * <p>{@link #freeSlugFor} is a check-then-act: two concurrent creates of
     * the same title both read "free" and one violates the constraint. Rather
     * than hold a lock for a cosmetic URL, the loser retries once with the
     * id-suffixed slug, which cannot collide. One retry is enough precisely
     * because the fallback is not a guess.
     */
    private Product saveResolvingSlugCollision(Product product) {
        try {
            return repository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            String fallback = idSuffixedSlug(Slug.from(product.title()), product.id());
            log.warn("Slug collision on save for title=\"{}\"; retrying as slug=\"{}\"",
                    product.title(), fallback);
            product.assignSlug(fallback);
            return repository.saveAndFlush(product);
        }
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
    public Optional<ProductResponse> update(UUID id, ProductUpdateRequest request) {
        Optional<ProductResponse> result = repository.findById(id).map(product -> {
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
            // Inside the transaction: the entity's categories, variants and
            // images are lazy, and mapping them in the controller threw
            // LazyInitializationException and answered 500.
            return ProductMapper.toResponse(product);
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
                    // Scheduled BEFORE the delete, because it reads the
                    // storage keys while the product_images rows still exist
                    // and defers the object removal until after this
                    // transaction commits. Object storage has no foreign keys,
                    // so the FK cascade that drops those rows cannot touch the
                    // objects -- without this every deleted product would
                    // leave its photographs in storage permanently.
                    imageService.reclaimStorageFor(id);
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
