package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.client.InventoryClient;
import com.oriah.catalog.client.InventoryDto;
import com.oriah.catalog.config.CatalogProperties;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.dto.VariantUpdateRequest;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.events.ProductCreatedEvent;
import com.oriah.catalog.events.VariantPayload;
import com.oriah.catalog.events.ProductDeletedEvent;
import com.oriah.catalog.events.ProductUpdatedEvent;
import com.oriah.catalog.constants.CatalogConstants;
import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.ProductTypeRegistry;
import com.oriah.catalog.model.AttributeValidationException;
import com.oriah.catalog.model.Category;
import com.oriah.catalog.repository.CategoryRepository;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductSort;
import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.model.Slug;
import com.oriah.catalog.model.StorefrontFilter;
import com.oriah.catalog.mapper.ProductMapper;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;

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
 *
 * <h2>The event log</h2>
 * Every write that changes what inventory and search know about a product
 * appends one event to catalog's outbox, in the same transaction, as its
 * last write: a full snapshot of the product ({@code product.created},
 * {@code product.updated}) or its id ({@code product.deleted}), with the
 * member of staff who made the change as the actor. The relay publishes the
 * outbox to {@code catalog.product}. Because the event commits with the
 * change, a price change can never be lost between the commit and the send,
 * and a change that rolls back is never announced.
 *
 * <p>Each such write on an existing product first locks the product's row
 * ({@link ProductRepository#findByIdForUpdate}), then changes it, flushes,
 * and appends. So two writes to one product take turns, and the snapshot
 * recorded last always includes both changes. A deleted product refuses
 * every such write ({@link #NOT_DELETED}), so product.deleted is always the
 * last event a product has.
 */
@Service
@Slf4j
public class ProductService {

    /** The topic, the aggregate and the payload version of every product event. */
    static final String TOPIC = "catalog.product";
    static final String AGGREGATE = "product";
    static final int SCHEMA_VERSION = 1;

    /**
     * Every write to an existing product applies this right after taking the
     * product's lock, so a deleted product answers 404 to all of them.
     *
     * <p>product.deleted must stay the last event a product ever has. Search
     * and inventory keep only the newest snapshot of a product, so a
     * product.updated numbered after the delete would put the deleted product
     * back on the storefront -- publish would even set it ACTIVE again. It is
     * checked after the lock, not before it, because a write that waited for a
     * delete to commit must see that delete.
     */
    private static final java.util.function.Predicate<Product> NOT_DELETED = product -> !product.isDeleted();

    /**
     * The lock every create takes before choosing a slug: the letters
     * "CATSLUGS" read as one number, distinct from the outbox's own
     * ("ORIAHOUT"). See {@link #lockSlugAssignment}.
     */
    static final long SLUG_LOCK_KEY = 0x4341_5453_4C55_4753L;

    private final ProductRepository repository;
    private final InventoryClient inventoryClient;
    private final OutboxWriter outbox;
    private final CatalogProperties props;
    private final CategoryService categoryService;
    // Resolving ids to entities is a read the CategoryService interface does
    // not expose -- it answers in response DTOs, which cannot be attached to a
    // product.
    private final CategoryRepository categoryRepository;
    private final ProductImageService imageService;
    // Joins the JPA transaction (JpaTransactionManager exposes its connection),
    // so the slug lock below is held until that transaction ends.
    private final JdbcTemplate jdbc;

    /**
     * How many readable discriminators to try before falling back to the id
     * suffix. Bounded so a pathological title cannot turn one create into an
     * unbounded number of queries.
     */
    private static final int MAX_SLUG_ATTEMPTS = 25;

    public ProductService(
            ProductRepository repository,
            InventoryClient inventoryClient,
            OutboxWriter outbox,
            CatalogProperties props,
            CategoryService categoryService,
            CategoryRepository categoryRepository,
            ProductImageService imageService,
            JdbcTemplate jdbc) {
        this.repository = repository;
        this.inventoryClient = inventoryClient;
        this.outbox = outbox;
        this.props = props;
        this.categoryService = categoryService;
        this.categoryRepository = categoryRepository;
        this.imageService = imageService;
        this.jdbc = jdbc;
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
                // A deleted product is gone from every read path. The row
                // survives only so that other services can still resolve the
                // variant id they hold; it is not catalogue content any more.
                .filter(product -> !product.isDeleted())
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
                // Packaging is never on the storefront. It cannot be ACTIVE, so
                // the line above already hides it; this one says so itself.
                .filter(product -> product.kind() == ProductKind.SELLABLE)
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
                // Hidden from the ADMIN read too. Otherwise "deleted" would
                // mean only "hidden from customers", and an edit through this
                // endpoint would resurrect something the audit trail says is
                // gone.
                .filter(product -> !product.isDeleted())
                .map(product -> ProductMapper.toResponse(
                        new ProductWithAvailability(product, lookupAvailability(product.id()))));
    }

    /**
     * The operator's list: every product that is not deleted, whatever its status
     * or kind, newest first.
     *
     * <p>Rows are the admin projection -- variants included, no availability. An
     * inventory call per row would turn one screen into N downstream calls, and the
     * operator reading this list is choosing a product, not checking its stock.
     *
     * @param text matched as plain text, case-insensitively, against the title and
     *             every variant's SKU
     * @param size clamped to {@link CatalogConstants#ADMIN_MAX_PAGE_SIZE}; a page
     *             past the end is simply empty
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> findForAdmin(ProductKind kind, ProductStatus status, String text,
                                              int page, int size) {
        // A NUL cannot be stored by PostgreSQL, so searching for one would reach
        // the database and come back as a "conflicts with existing data" 409. It
        // is the caller's mistake: a 400 that says so.
        if (text != null && text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("q must not contain the NUL character");
        }
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), CatalogConstants.ADMIN_MAX_PAGE_SIZE);

        List<UUID> ids = repository.findAdminIds(kind, status, text, pageNumber, pageSize);
        long total = repository.countAdmin(kind, status, text);
        List<ProductResponse> content = repository.findAllWithDetailByIdIn(ids).stream()
                .map(ProductMapper::toAdminResponse)
                .toList();

        log.debug("findForAdmin kind={} status={} textGiven={} -> {} of {}",
                kind, status, text != null && !text.isBlank(), content.size(), total);
        return new org.springframework.data.domain.PageImpl<>(
                content, org.springframework.data.domain.PageRequest.of(pageNumber, pageSize), total);
    }

    /**
     * Adds a purchasable configuration to a product.
     *
     * <p>The product owns the invariants, not the variant: the option key set
     * must match the product's declared axes, the combination must not already
     * exist, and the currency must match its siblings. A variant cannot check
     * any of those, because it cannot see the others.
     *
     * @param actor who is making the change, {@code user:<keycloak subject>};
     *              recorded on the event
     * @throws IllegalArgumentException on any of those violations, or an
     *         unknown product id; rendered as a 400 by the shared advice
     */
    @Transactional
    public Optional<ProductResponse> addVariant(UUID productId, VariantCreateRequest request, String actor) {
        // Locked first, so a concurrent write to this product waits for this
        // one and its snapshot includes this variant (see findByIdForUpdate).
        return repository.findByIdForUpdate(productId).filter(NOT_DELETED).map(product -> {
            ProductVariant variant = new ProductVariant(
                    product,
                    request.sku(),
                    request.unitPrice(),
                    request.currency(),
                    request.options());
            product.addVariant(variant);
            log.info("Added variant sku=\"{}\" to product id={} price={} {}",
                    variant.sku(), productId, variant.unitPrice(), variant.currency());
            // Recorded so inventory learns about the new variant. Without this,
            // a variant added after creation would never reach product_index
            // and the admin inventory screen would show a bare UUID with no
            // title -- or nothing at all.
            record(product, ProductUpdatedEvent.TYPE, productUpdated(product), actor);
            // The ADMIN projection: the caller needs to see the variant it
            // just created, which the list mapping omits.
            return ProductMapper.toAdminResponse(product);
        });
    }

    /**
     * Reprices one of a product's existing variants.
     *
     * <h2>Why this routes through the aggregate</h2>
     *
     * {@code ProductVariant.reprice} is public and would do the job, and would
     * bypass both rules that live on {@link Product} because a variant cannot
     * see its siblings: one currency per product, so a card never shows two,
     * and the refusal to leave a published product with nothing purchasable.
     *
     * <h2>Why an absent currency is resolved HERE</h2>
     *
     * {@code ProductVariant.normaliseCurrency} reads a null as ZMW, so passing
     * the absent value through would redenominate a dollar piece to Kwacha at
     * the same number -- or, on a multi-variant product, be refused by the
     * uniformity rule against its own siblings, with no currency control on
     * the admin form to correct it with. A PATCH that omits a field leaves it
     * alone, so the variant's current currency is what gets passed.
     *
     * @param actor who is making the change, {@code user:<keycloak subject>};
     *              recorded on the event
     * @return empty when the product does not exist OR carries no such
     *         variant, so the caller can answer 404 for both. The aggregate
     *         reports an unknown variant as IllegalArgumentException, which
     *         the shared advice renders 400 -- the wrong thing to tell someone
     *         who addressed a variant that is not there.
     */
    @Transactional
    public Optional<ProductResponse> repriceVariant(
            UUID productId, UUID variantId, VariantUpdateRequest request, String actor) {
        // Locked first: a concurrent reprice of another variant of this
        // product waits for this one to commit, then reads this price, so
        // whichever snapshot is recorded last carries both.
        return repository.findByIdForUpdate(productId)
                .filter(NOT_DELETED)
                .flatMap(product -> product.variants().stream()
                .filter(v -> v.id().equals(variantId))
                .findFirst()
                .map(variant -> {
                    String currency = request.currency() == null || request.currency().isBlank()
                            ? variant.currency()
                            : request.currency();
                    BigDecimal was = variant.unitPrice();

                    product.repriceVariant(variantId, request.unitPrice(), currency);

                    log.info("Repriced variant id={} sku=\"{}\" on product id={} from {} to {} {}",
                            variantId, variant.sku(), productId, was,
                            variant.unitPrice(), variant.currency());
                    // The same event addVariant records. Its VariantPayload
                    // carries unitPrice and currency, so the log holds every
                    // price the product has ever had, and when -- prices
                    // decide revenue, which is why this is a logged fact.
                    record(product, ProductUpdatedEvent.TYPE, productUpdated(product), actor);
                    return ProductMapper.toAdminResponse(product);
                }));
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
     * @param actor who is making the change, {@code user:<keycloak subject>};
     *              recorded on the event
     * @throws IllegalStateException when the product is not publishable, which
     *         the shared advice renders as a 409 -- a conflict with the
     *         product's current state rather than a malformed request
     */
    @Transactional
    public Optional<ProductResponse> publish(UUID productId, String actor) {
        // A deleted product is archived; publishing it would set it ACTIVE
        // again, in the database and in the log.
        return repository.findByIdForUpdate(productId).filter(NOT_DELETED).map(product -> {
            product.publish();
            log.info("Published product id={} slug=\"{}\"", productId, product.slug());
            // Publication changes `status` on the event, which is how a
            // consumer can distinguish a live product from a draft without
            // asking catalog.
            record(product, ProductUpdatedEvent.TYPE, productUpdated(product), actor);
            return ProductMapper.toAdminResponse(product);
        });
    }

    /**
     * Creates and persists a new product. Title is required and validated by
     * the {@link Product} domain rule; description and tags are optional.
     * The repository assigns no id of its own — {@link Product} generates a
     * UUID at construction.
     */
    @Transactional
    public ProductResponse create(String title, String description, List<String> tags, String actor) {
        return create(title, description, tags, null, null, null, null, actor);
    }

    /**
     * Creates a product of a given TYPE, with that type's specification.
     *
     * <p>Everything this needs already existed and had no way in:
     * {@link ProductTypeRegistry} declares JEWELLERY and WATCH with their
     * attributes and validates them, {@link Product#applyType} applies the
     * result, {@code products.attributes} is a JSONB column, and
     * {@code ProductTypeConsistencyVerifier} refuses to start the service if
     * the table and the registry disagree. {@code ProductCreateRequest} simply
     * did not carry the two fields, so every product created through the API
     * was a JEWELLERY with an empty spec, and the WATCH type -- defined,
     * seeded, validated and verified at startup -- was unreachable.
     *
     * @param typeCode   null means JEWELLERY; see the DTO for why that default
     *                   is preserved rather than made explicit
     * @param attributes validated against what the TYPE declares, so a carat
     *                   weight on a watch is refused rather than stored
     * @param actor      who is creating it, {@code user:<keycloak subject>};
     *                   recorded on the event
     * @throws com.oriah.catalog.model.AttributeValidationException
     *         the type is unknown, or one or more attributes are wrong -- 400,
     *         with every problem reported at once
     */
    @Transactional
    public ProductResponse create(String title, String description, List<String> tags,
                                  String typeCode, Map<String, Object> attributes,
                                  List<UUID> categoryIds, UUID primaryCategoryId, String actor) {
        return create(title, description, tags, typeCode, attributes, categoryIds, primaryCategoryId,
                null, null, actor);
    }

    /**
     * As above, for a given {@link ProductKind} and with the axes its variants
     * vary on.
     *
     * @param kind        null means SELLABLE, so every caller that predates
     *                    packaging is unchanged. Packaging is typed PACKAGING and
     *                    is never published or priced; see {@link ProductKind}.
     * @param optionNames the axes the variants vary on ({@code ["Ring Size"]});
     *                    null or empty means the product does not vary. Declared
     *                    here, before any variant exists, because each variant's
     *                    options must match them exactly.
     */
    @Transactional
    public ProductResponse create(String title, String description, List<String> tags,
                                  String typeCode, Map<String, Object> attributes,
                                  List<UUID> categoryIds, UUID primaryCategoryId,
                                  ProductKind kind, List<String> optionNames, String actor) {
        // Tag-count logging is friendlier than printing the full list: keeps
        // the line scannable and avoids leaking large payloads at INFO.
        int tagCount = tags == null ? 0 : tags.size();
        Product product = new Product(UUID.randomUUID(), title, description, tags,
                kind == null ? ProductKind.SELLABLE : kind);
        applyTypeAndAttributes(product, typeCode, attributes);
        if (optionNames != null) {
            product.declareOptionNames(optionNames);
        }
        // Applied HERE as well as on update, and the omission was a live bug:
        // the field was on the DTO and wired only into the update path, so a
        // create carrying categoryIds was accepted and silently filed the
        // product nowhere. A field on the wire that means nothing is worse
        // than no field, because the caller has every reason to believe it
        // worked.
        if (categoryIds != null) {
            applyCategories(product, categoryIds, primaryCategoryId);
        }
        // Taken after the checks above, so a refused create never queues
        // behind others; held until this create commits.
        lockSlugAssignment();
        product.assignSlug(freeSlugFor(product));
        Product saved = repository.saveAndFlush(product);
        log.info("Created product id={} title=\"{}\" kind={} tagCount={}",
                saved.id(), saved.title(), saved.kind(), tagCount);
        // No row lock to take first: the row was inserted by this transaction a
        // moment ago, nobody else can see it until the commit, and the insert
        // already holds it. Tag list is copied via List.copyOf to decouple
        // the event from the entity's mutable internal storage.
        record(saved, ProductCreatedEvent.TYPE, new ProductCreatedEvent(
                saved.id(),
                saved.title(),
                saved.tags() == null ? List.of() : List.copyOf(saved.tags()),
                saved.productTypeCode(),
                saved.status().name(),
                // Empty on create: a product is created before it has any
                // variants, so this is genuinely the empty list rather than a
                // placeholder. Inventory registers nothing for it, which is
                // correct -- there is nothing purchasable to track stock
                // against yet. The variants arrive on the first
                // product.updated after a variant is added.
                variantPayloads(saved),
                saved.kind().name()
        ), actor);
        // Mapped inside the transaction like every other read path here. A
        // freshly constructed product would in fact survive mapping outside
        // it -- its collections are real collections, not Hibernate proxies --
        // which is exactly why doing it consistently matters: create would
        // keep working while update failed, and the difference is invisible
        // at the call site.
        return ProductMapper.toAdminResponse(saved);
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
     *
     * <p>Its answer holds only under {@link #lockSlugAssignment}: a check
     * followed by an insert, and another create could otherwise take the same
     * slug in between.
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
     * Makes creates take turns at choosing a slug, until each commits.
     *
     * <p>{@link #freeSlugFor} is a check-then-act. Without this, two creates
     * of the same title at the same moment both found the slug free (the
     * first's insert is invisible to the second until it commits), and the
     * second's insert broke the UNIQUE constraint. It used to catch that and
     * save again with a fallback slug -- which could never work: Postgres
     * refuses every further statement in a transaction once one has failed,
     * and Spring had already marked the transaction to roll back. So the
     * admin got an error.
     *
     * <p>With this lock the second create waits here until the first commits,
     * then sees its slug taken and picks the next ({@code -2}). One lock for
     * every create, not one per title: "Ring" (its {@code ring} taken, so
     * trying {@code ring-2}) and "Ring 2" race for the same slug from
     * different titles. Creates are rare admin actions, and each already
     * waits for the outbox's lock at its end, so this costs nothing that
     * matters. It is a transaction lock: Postgres lets it go at the commit or
     * rollback, never earlier.
     */
    private void lockSlugAssignment() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (RowCallbackHandler) row -> { }, SLUG_LOCK_KEY);
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
     *
     * @param actor who is making the change, {@code user:<keycloak subject>};
     *              recorded on the event
     */
    @Transactional
    public Optional<ProductResponse> update(UUID id, ProductUpdateRequest request, String actor) {
        Optional<ProductResponse> result = repository.findByIdForUpdate(id)
                .filter(NOT_DELETED)
                .map(product -> {
            // Track which fields actually changed so the log line is useful
            // when investigating "why did this product's title change?"
            StringBuilder changedFields = new StringBuilder();
            // The kind is fixed at creation. Naming it again is harmless; naming
            // a different one is refused here, before anything else is applied,
            // so the request changes nothing and says why.
            if (request.kind() != null && request.kind() != product.kind()) {
                throw new IllegalArgumentException(
                        "a product's kind is fixed when it is created: this one is "
                                + product.kind() + " and cannot become " + request.kind());
            }
            if (request.title() != null) {
                product.updateTitle(request.title());
                changedFields.append("title ");
            }
            // Refused by the product while an active variant's options would
            // stop matching, naming that variant (400), so a rename of an axis
            // can never leave a variant the storefront cannot build a selector for.
            if (request.optionNames() != null) {
                product.declareOptionNames(request.optionNames());
                changedFields.append("optionNames ");
            }
            if (request.description() != null) {
                product.updateDescription(request.description());
                changedFields.append("description ");
            }
            if (request.tags() != null) {
                product.updateTags(request.tags());
                changedFields.append("tags ");
            }
            // PATCH is partial, so a request mentioning NEITHER leaves both
            // alone -- a title edit that silently blanked a watch's spec would
            // be the kind of data loss nobody notices until a listing goes
            // thin. Mentioning EITHER re-validates the pair together, because
            // attributes are only meaningful against a type: changing the type
            // alone still has to re-check the spec already stored against it.
            if (request.categoryIds() != null) {
                applyCategories(product, request.categoryIds(), request.primaryCategoryId());
                changedFields.append("categories ");
            }
            if (request.productType() != null || request.attributes() != null) {
                applyTypeAndAttributes(
                        product,
                        request.productType() != null
                                ? request.productType()
                                : product.productTypeCode(),
                        request.attributes() != null
                                ? request.attributes()
                                : product.attributes());
                changedFields.append("type/attributes ");
            }
            log.info("Updated product id={} changedFields=[{}]", product.id(), changedFields.toString().trim());
            // Snapshot the post-update state into the event. We send the
            // current values for *all* fields (not just changed ones) so
            // consumers don't need a prior read to reconstruct state.
            record(product, ProductUpdatedEvent.TYPE, productUpdated(product), actor);
            // Inside the transaction: the entity's categories, variants and
            // images are lazy, and mapping them in the controller threw
            // LazyInitializationException and answered 500.
            return ProductMapper.toAdminResponse(product);
        });

        if (result.isEmpty()) {
            log.debug("Update skipped, product not found id={}", id);
        }
        return result;
    }

    /**
     * Puts a product in categories, replacing whatever it was in.
     *
     * <p>Ids are resolved before anything is applied, so a request naming one
     * unknown category changes nothing rather than half-filing the product.
     * {@code Product.assignCategories} then enforces that the primary is one
     * of them.
     */
    private void applyCategories(Product product, List<UUID> categoryIds, UUID primaryId) {
        var resolved = new java.util.LinkedHashSet<Category>();
        for (UUID categoryId : categoryIds) {
            resolved.add(categoryRepository.findById(categoryId).orElseThrow(() ->
                    new IllegalArgumentException("no category with id " + categoryId)));
        }
        Category primary = primaryId == null
                ? null
                : categoryRepository.findById(primaryId).orElseThrow(() ->
                        new IllegalArgumentException("no category with id " + primaryId));
        product.assignCategories(resolved, primary);
    }

    /**
     * Validates a type and its specification together, and applies them.
     *
     * <p>The type is checked against the registry rather than left to the
     * database: {@code products.product_type_code} carries a foreign key to
     * {@code product_types}, so an unknown value would reach Postgres and come
     * back as a constraint violation rendered 500, when it is plainly a bad
     * request. The message names the types this build knows, because an
     * operator who mistyped one can act on that.
     */
    private void applyTypeAndAttributes(Product product, String typeCode,
                                        Map<String, Object> attributes) {
        // Absent means the type this product already has: the constructor
        // gave a new one the type its kind calls for (JEWELLERY for something
        // for sale, PACKAGING for packaging), so "absent" is right for both.
        String code = typeCode == null || typeCode.isBlank()
                ? product.productTypeCode()
                : typeCode.trim().toUpperCase();

        if (!ProductTypeRegistry.knownCodes().contains(code)) {
            throw new AttributeValidationException(List.of(Map.entry(
                    "productType",
                    "unknown product type. Known types: "
                            + new java.util.TreeSet<>(ProductTypeRegistry.knownCodes()))));
        }

        // Throws AttributeValidationException listing EVERY problem, so an
        // operator filling six spec fields learns about all six at once.
        product.applyType(code, ProductTypeRegistry.validate(code, attributes));
    }

    /**
     * Appends one product event to the outbox, in the caller's transaction,
     * as its last write.
     *
     * <p>Flushed first, so the change itself reaches the database -- and any
     * constraint it breaks fails here -- before the append takes the outbox's
     * lock, which is then held only until the commit a moment later.
     *
     * <p>{@code occurredAt} is now: a catalog edit takes effect the moment it
     * is made, so the time it happened and the time it is recorded are the
     * same.
     */
    private void record(Product product, String eventType, Object payload, String actor) {
        repository.flush();
        outbox.append(new EventDraft(AGGREGATE, product.id().toString(), eventType, SCHEMA_VERSION, TOPIC,
                Instant.now(), actor, payload));
    }

    /**
     * A full post-change snapshot, not a delta.
     *
     * <p>Consumers can then apply an update without having read the previous
     * state. Each event also carries its {@code seq} within the product, so
     * a consumer that receives an older snapshot after a newer one (a
     * redelivery, a replay) can recognise it and skip it. A full snapshot
     * applied twice converges; a delta would not.
     */
    private static ProductUpdatedEvent productUpdated(Product product) {
        return new ProductUpdatedEvent(
                product.id(),
                product.title(),
                product.description().orElse(null),
                product.tags() == null ? List.of() : List.copyOf(product.tags()),
                product.productTypeCode(),
                product.status().name(),
                variantPayloads(product),
                product.kind().name());
    }

    /**
     * Every variant, including inactive ones, each with the title inventory
     * will display.
     *
     * <p>Inactive variants are included deliberately: a consumer that only
     * ever saw active ones would have to infer a withdrawal from a variant's
     * ABSENCE, which is indistinguishable from the variant simply not having
     * been created yet. The {@code active} flag says it outright.
     */
    private static List<VariantPayload> variantPayloads(Product product) {
        return product.variants().stream()
                .map(variant -> new VariantPayload(
                        variant.id(),
                        variant.sku(),
                        variantTitle(product, variant),
                        variant.unitPrice(),
                        variant.currency(),
                        variant.isActive()))
                .toList();
    }

    /**
     * "Aurelia Solitaire Ring - Ring Size M".
     *
     * <p>Composed here rather than left to the consumer: inventory stores it
     * verbatim, and an admin stock screen listing bare UUIDs or repeating the
     * same product title across six rows is not something an operator can act
     * on. Ordered by the product's declared axes so two variants of one
     * product always read in the same order.
     */
    private static String variantTitle(Product product, ProductVariant variant) {
        if (variant.options().isEmpty()) {
            return product.title();
        }
        String options = product.optionNames().stream()
                .filter(variant.options()::containsKey)
                .map(axis -> axis + " " + variant.options().get(axis))
                .collect(java.util.stream.Collectors.joining(", "));
        return options.isEmpty() ? product.title() : product.title() + " - " + options;
    }

    /**
     * Deletes the product with the given id. Returns true if a product was
     * found and removed, false if no product had that id. The two-step
     * lookup-then-delete lets the controller return 204 vs 404 unambiguously
     * — {@code repository.deleteById} is silent when the id is missing.
     *
     * @param actor who is deleting it, {@code user:<keycloak subject>};
     *              recorded on the event
     */
    @Transactional
    public boolean deleteById(UUID id, String actor) {
        boolean removed = repository.findByIdForUpdate(id)
                .filter(NOT_DELETED)
                .map(product -> {
                    // Image OBJECTS are still reclaimed: nobody will ever
                    // serve them again, and object storage is the one place
                    // where keeping the bytes buys nothing. The product_images
                    // ROWS survive along with the product, which is what lets
                    // an order's history still describe what was bought.
                    imageService.reclaimStorageFor(id);

                    // SOFT, not hard, and this is the single most consequential
                    // line in the service.
                    //
                    // A hard delete cascaded through product_variants and
                    // removed the row whose id is a primary key in
                    // inventory.inventory, inventory.product_index and
                    // search.search_index, and the line identity in cart_items
                    // and order_items. None of those has a foreign key into
                    // catalog, because none shares its database -- so nothing
                    // stopped it, and two things broke:
                    //
                    //   order_items, a deliberately immutable financial
                    //   record, would point at a variant that no longer
                    //   existed, so a past order could not be resolved to
                    //   what was actually sold.
                    //
                    //   The pricing endpoint's answer changed from
                    //   UNPUBLISHED to UNKNOWN, collapsing the distinction it
                    //   exists to provide: a bag can say "no longer
                    //   available" for the first and "this line is corrupt"
                    //   for the second, and after a hard delete it could no
                    //   longer tell them apart.
                    //
                    // Archived as well as flagged. The flag is what every read
                    // path filters on; the status is what anything reading the
                    // row directly sees, and leaving a deleted product ACTIVE
                    // would be a lie told to whoever looks next.
                    product.archive();
                    product.markDeleted();
                    repository.saveAndFlush(product);
                    // Only the id, as product.deleted has always carried:
                    // a consumer removes what it holds for it. Its seq is
                    // what keeps a late, older snapshot from bringing the
                    // product back in a consumer that skips older seqs.
                    record(product, ProductDeletedEvent.TYPE, new ProductDeletedEvent(id), actor);
                    return true;
                })
                .orElse(false);

        if (removed) {
            log.info("Deleted product id={}", id);
        } else {
            log.debug("Delete skipped, product not found id={}", id);
        }
        return removed;
    }
}
