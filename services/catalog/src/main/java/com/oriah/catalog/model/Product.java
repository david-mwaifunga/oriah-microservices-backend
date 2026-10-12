package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.oriah.jpa.BaseEntity;

/**
 * Product aggregate root for the catalog domain.
 *
 * <p>This is both the JPA entity (mapped to {@code products} +
 * {@code product_tags} tables by {@code V1__create_products.sql}) and the
 * in-memory domain model. The class enforces its invariants in the
 * constructor and update methods so business rules stay close to the data.
 *
 * <p>Identity is a UUID generated at construction (not by the database) so
 * callers can refer to a product before it has been persisted.
 */
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_tags", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "tag", nullable = false)
    private List<String> tags = new ArrayList<>();

    /**
     * URL identity, unique across the catalogue. Generated from the title when
     * not supplied; {@code V3} backfilled it for every pre-existing product
     * using the same rules {@link Slug} applies, so a title produces the same
     * slug whichever side of the migration it was created on.
     */
    @Column(nullable = false, unique = true)
    private String slug;

    /**
     * Which product type's spec fields this product carries. A foreign key to
     * {@code product_types}; the matching attribute definitions live in
     * {@link ProductTypeRegistry}.
     */
    @Column(name = "product_type_code", nullable = false)
    private String productTypeCode;

    /**
     * Type-specific specs, validated against {@link ProductTypeRegistry}
     * before they ever reach this field, so the column never holds an
     * unvalidated or undeclared key.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> attributes = new LinkedHashMap<>();

    /**
     * The axes this product's variants vary on, in display order: {@code
     * ["Ring Size"]}, or {@code ["Strap", "Case Size"]}. Empty means the
     * product does not vary — a one-off piece with a single default variant,
     * which is what the V3 backfill produced for the whole existing catalogue.
     *
     * <p>Every variant's option key SET must equal this exactly, so the
     * storefront can build selectors generically instead of guessing which
     * keys a given product uses.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "option_names", nullable = false)
    private String[] optionNames = new String[0];

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    /**
     * The category used for breadcrumbs and the canonical URL. Must also be a
     * member of {@link #categories} — otherwise a breadcrumb points at a
     * category the product is not in. That is a cross-row rule the database
     * cannot express in this shape, so it is enforced in the service and
     * tested there.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_category_id")
    private Category primaryCategory;

    /**
     * Many-to-many, because a watch legitimately belongs in both "Watches" and
     * "Gifts". Distinct from tags, which stay as the flat, open label set.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @JoinTable(name = "product_categories",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> categories = new LinkedHashSet<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    @BatchSize(size = 50)
    private List<ProductImage> images = new ArrayList<>();

    protected Product() {
        // For JPA hydration only — do not call from application code.
    }

    public Product(String title) {
        this(UUID.randomUUID(), title, null, null);
    }

    public Product(String title, String description) {
        this(UUID.randomUUID(), title, description, null);
    }

    public Product(String title, String description, List<String> tags) {
        this(UUID.randomUUID(), title, description, tags);
    }

    /**
     * Reconstitution constructor used when the id is already known —
     * e.g. mapping from a persisted record, mapping from a request DTO that
     * supplies one, or constructing test fixtures with a deterministic id.
     *
     * <p>Public because, under the layer-by-layer package layout, controllers
     * and tests in other packages legitimately need to materialize a Product
     * with a specific id. With aggregate-per-package this could stay
     * package-private, but the encapsulation gain isn't worth duplicating
     * factory methods across packages.
     */
    public Product(UUID id, String title, String description, List<String> tags) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.id = id;
        this.title = title;
        this.description = description;
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);

        // Phase 7 defaults. Each is chosen so that adding these columns did
        // not break the admin create contract or let anything unpriced sell:
        //
        //   status DRAFT          nothing reaches the storefront until it has
        //                         a real price; also where V3 left every
        //                         migrated product
        //   type   JEWELLERY      so productTypeCode is not a NEW REQUIRED
        //                         request field, which would have been a
        //                         breaking change to POST /admin/products
        //   slug   from the title generated the same way V3 backfilled it, so
        //                         one title yields one slug either side of the
        //                         migration. The service resolves collisions,
        //                         since the column is UNIQUE.
        this.status = ProductStatus.DRAFT;
        this.productTypeCode = ProductTypeRegistry.JEWELLERY;
        this.slug = Slug.from(title);
    }

    public UUID id() {
        return id;
    }

    public String title() {
        return title;
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public List<String> tags() {
        return List.copyOf(tags);
    }

    /**
     * Updates the product's title. Same invariant as construction:
     * must not be blank.
     */
    public void updateTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = newTitle;
    }

    /**
     * Replaces the product's description. Null is rejected via DTO-level
     * validation in {@code ProductUpdateRequest}; if called with null
     * directly, the field is set to null (use this only for non-null
     * updates routed through the service).
     */
    public void updateDescription(String newDescription) {
        this.description = newDescription;
    }

    /**
     * Replaces the product's tag list with a fresh copy.
     */
    public void updateTags(List<String> newTags) {
        this.tags = newTags == null ? new ArrayList<>() : new ArrayList<>(newTags);
    }

    // ------------------------------------------------------------------
    // Phase 7: type, taxonomy, variants, images
    // ------------------------------------------------------------------

    public String slug() {
        return slug;
    }

    public String productTypeCode() {
        return productTypeCode;
    }

    public Map<String, Object> attributes() {
        return Map.copyOf(attributes);
    }

    public List<String> optionNames() {
        return List.of(optionNames);
    }

    public ProductStatus status() {
        return status;
    }

    public Optional<Category> primaryCategory() {
        return Optional.ofNullable(primaryCategory);
    }

    public Set<Category> categories() {
        return Set.copyOf(categories);
    }

    public List<ProductVariant> variants() {
        return List.copyOf(variants);
    }

    public List<ProductImage> images() {
        return List.copyOf(images);
    }

    /** Slug is settable so the service can resolve a collision with a suffix. */
    public void assignSlug(String newSlug) {
        if (newSlug == null || newSlug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        this.slug = newSlug.trim();
    }

    /**
     * Sets the product's type and validates its attributes against that
     * type in one step, because the two are only meaningful together: the
     * attributes a product may carry are entirely a function of its type.
     */
    public void applyType(String newTypeCode, Map<String, Object> newAttributes) {
        // Validated BEFORE either field is assigned, so a rejected change
        // leaves the product exactly as it was rather than half-applied with
        // a new type and the old type's attributes.
        Map<String, Object> validated = ProductTypeRegistry.validate(newTypeCode, newAttributes);
        this.productTypeCode = newTypeCode;
        this.attributes = new LinkedHashMap<>(validated);
    }

    /**
     * Declares the axes this product's variants vary on.
     *
     * @throws IllegalArgumentException if any existing variant's option keys
     *         would no longer match — otherwise a rename would silently leave
     *         variants the storefront cannot build a selector for
     */
    public void declareOptionNames(List<String> newOptionNames) {
        List<String> declared = newOptionNames == null ? List.of() : List.copyOf(newOptionNames);
        var keys = new LinkedHashSet<String>();
        for (String name : declared) {
            // Normalised through the same path a variant's option keys take,
            // so "Ring Size" declared here and submitted there cannot differ
            // by whitespace or Unicode form and then fail to match.
            String normalised = VariantOptions.validated(Map.of(name, "x")).keySet().iterator().next();
            if (!keys.add(normalised)) {
                throw new IllegalArgumentException("duplicate option name: " + normalised);
            }
        }

        // Refused while any existing variant would stop matching. A silent
        // change would leave variants whose key set no longer corresponds to
        // any axis, which the storefront cannot build a selector from and the
        // admin cannot edit back into shape.
        // ACTIVE variants only, which makes the error message below honest.
        // It tells the caller to "update or deactivate the variants first" --
        // and until this filter existed, deactivating changed nothing because
        // the loop consulted every variant regardless. That left the whole
        // migrated catalogue unable to gain option axes: V3 gave every
        // pre-existing product one variant with empty options, so adding a
        // "Ring Size" axis hit this refusal, and the remedy it named did not
        // work. An inactive variant is not for sale and the storefront builds
        // no selector from it, so it has no stake in the axes.
        for (ProductVariant variant : variants) {
            if (!variant.isActive()) {
                continue;
            }
            if (!variant.options().keySet().equals(keys)) {
                throw new IllegalArgumentException(
                        "cannot change option axes to " + keys
                                + " while active variant " + variant.sku()
                                + " has options " + variant.options().keySet()
                                + "; update or deactivate the variants first");
            }
        }
        this.optionNames = keys.toArray(String[]::new);
    }

    /**
     * Adds a variant, enforcing both invariants that make the variant list
     * coherent: its option keys must match {@link #optionNames()}, and its
     * option combination must not already be present.
     *
     * <p>The duplicate check here is a courtesy that produces a clear error;
     * the guarantee is the {@code UNIQUE (product_id, option_digest)}
     * constraint, which is what actually holds when two admins save at once.
     */
    public ProductVariant addVariant(ProductVariant variant) {
        if (variant == null) {
            throw new IllegalArgumentException("variant must not be null");
        }
        Set<String> axes = Set.of(optionNames);
        if (!variant.options().keySet().equals(axes)) {
            throw new IllegalArgumentException(
                    "variant options " + variant.options().keySet()
                            + " must match the product's option axes " + axes);
        }
        requireUniformCurrency(variant.currency(), variant);
        boolean duplicate = variants.stream()
                .anyMatch(existing -> existing.optionDigest().equals(variant.optionDigest()));
        if (duplicate) {
            throw new IllegalArgumentException(
                    "a variant with options " + variant.options() + " already exists");
        }
        variant.setPosition(variants.size());
        variants.add(variant);
        return variant;
    }

    public void addImage(ProductImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        image.setPosition(images.size());
        images.add(image);
    }

    public boolean removeImage(UUID imageId) {
        boolean removed = images.removeIf(i -> i.id().equals(imageId));
        if (removed) {
            // Positions are renumbered so the gallery has no gap. The caller
            // deletes the stored object separately: that is not transactional,
            // so it happens after the commit that removes this row.
            for (int i = 0; i < images.size(); i++) {
                images.get(i).setPosition(i);
            }
        }
        return removed;
    }

    public void assignCategories(Set<Category> newCategories, Category newPrimary) {
        Set<Category> replacement = newCategories == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(newCategories);

        // The breadcrumb invariant: a primary category the product is not in
        // would render a trail pointing somewhere the product does not
        // appear, which is worse than rendering no trail. The database cannot
        // express this in the current shape, so it is enforced here.
        if (newPrimary != null && !replacement.contains(newPrimary)) {
            throw new IllegalArgumentException(
                    "primary category '" + newPrimary.slug()
                            + "' must also be one of the product's categories");
        }
        this.categories = replacement;
        this.primaryCategory = newPrimary;
    }

    /**
     * Whether this product may be published: at least one variant that is
     * active and priced above zero.
     *
     * <p>Zero is the V3 backfill's marker for "nobody has priced this", so a
     * zero-priced variant must not count. Without this rule the migration
     * would have made the entire existing catalogue free.
     */
    public boolean isPublishable() {
        return variants.stream().anyMatch(ProductVariant::isPurchasable);
    }

    /**
     * @throws IllegalStateException if the product is not {@link
     *         #isPublishable()} — rendered as a 409 by the shared error
     *         contract
     */
    public void publish() {
        if (!isPublishable()) {
            throw new IllegalStateException(
                    "product '" + slug + "' cannot be published: it needs at least one "
                            + "active variant priced above zero");
        }
        this.status = ProductStatus.ACTIVE;
    }

    /**
     * The one currency every variant of this product shares, or empty when it
     * has no variants yet.
     *
     * <p>Uniformity is an invariant, not an observation — see
     * {@link #requireUniformCurrency}. Empty rather than a default, because a
     * product with no variants has no price and guessing a currency for it
     * would put a figure on a card that nobody set.
     */
    public Optional<String> currency() {
        return variants.stream().map(ProductVariant::currency).findFirst();
    }

    /**
     * Cheapest price a customer can actually pay.
     *
     * <p>Restricted to purchasable variants — active and priced above zero —
     * for two separate reasons. Including an inactive variant would advertise
     * "from K4,000" when the cheapest buyable option is K4,500, and including
     * a zero would make every product migrated by V3 advertise "from K0.00".
     */
    public Optional<BigDecimal> lowestActivePrice() {
        return variants.stream()
                .filter(ProductVariant::isPurchasable)
                .map(ProductVariant::unitPrice)
                .min(BigDecimal::compareTo);
    }

    /** Dearest purchasable variant, for a "K4,000 - K5,000" range on a card. */
    public Optional<BigDecimal> highestActivePrice() {
        return variants.stream()
                .filter(ProductVariant::isPurchasable)
                .map(ProductVariant::unitPrice)
                .max(BigDecimal::compareTo);
    }

    /**
     * Reprices one of this product's variants. Routed through the product
     * rather than called on the variant directly because the currency rule is
     * a property of the SET of variants, and the variant cannot see its
     * siblings.
     */
    public void repriceVariant(UUID variantId, BigDecimal newPrice, String newCurrency) {
        ProductVariant variant = requireVariant(variantId);
        requireUniformCurrency(newCurrency, variant);
        // Zero is not a price, so repricing to zero makes a variant
        // unpurchasable just as surely as deactivating it. Without this the
        // two mutators disagreed: deactivateVariant refused to strand a
        // published product while repriceVariant walked straight into the
        // same state through another door.
        if (newPrice != null && newPrice.signum() == 0) {
            requireNotLastPurchasable(variant, "repriced to zero");
        }
        variant.reprice(newPrice, newCurrency);
    }

    /**
     * Withdraws a variant from sale.
     *
     * @throws IllegalStateException when the product is ACTIVE and this is its
     *         last purchasable variant. Publishing requires a purchasable
     *         variant; without this inverse rule an operator could leave a
     *         product live on the storefront with nothing to buy — and because
     *         the list query joins active variants to compute its price range,
     *         that product would disappear from the grid while still answering
     *         on its own URL.
     *
     *         <p>Refused rather than silently unpublishing: quietly pulling a
     *         live product off the storefront because of an edit to one
     *         variant is a bigger surprise than an error naming the variant
     *         that is load-bearing.
     */
    public void deactivateVariant(UUID variantId) {
        ProductVariant variant = requireVariant(variantId);
        requireNotLastPurchasable(variant, "deactivated");
        variant.deactivate();
    }

    /**
     * Refuses a change that would leave a PUBLISHED product with nothing
     * purchasable.
     *
     * <p>Shared by {@link #deactivateVariant} and {@link #repriceVariant},
     * which is the point: both reach the same end state — repricing to zero
     * makes a variant unpurchasable exactly as deactivating it does, because
     * {@link ProductVariant#isPurchasable()} requires a price above zero — and
     * having the rule in one place is what stops them drifting apart again.
     *
     * <p>The restriction applies only while the product is ACTIVE. A draft is
     * a work in progress and an archived product is already withdrawn; the
     * rule exists to protect the storefront, so it applies only while the
     * product is on it.
     */
    private void requireNotLastPurchasable(ProductVariant variant, String change) {
        if (status != ProductStatus.ACTIVE) {
            return;
        }
        boolean anotherRemains = variants.stream()
                .filter(v -> !v.id().equals(variant.id()))
                .anyMatch(ProductVariant::isPurchasable);
        if (!anotherRemains) {
            throw new IllegalStateException(
                    "variant " + variant.sku() + " is the last purchasable variant of the "
                            + "published product '" + slug + "' and cannot be " + change
                            + "; price another variant or withdraw the product first");
        }
    }

    private ProductVariant requireVariant(UUID variantId) {
        return variants.stream()
                .filter(v -> v.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no variant " + variantId + " on product '" + slug + "'"));
    }

    /**
     * A product's variants must all be priced in one currency.
     *
     * <p>The storefront card shows a single "from" price, so a product priced
     * in two currencies has no correct card — "from K4,500" is wrong if the
     * cheapest variant is in dollars, and two currencies on one tile is not a
     * price. It also keeps the single-currency-per-cart rule cheap to enforce,
     * since one product cannot contribute two currencies by itself.
     *
     * @param ignoring the variant being changed, excluded from the comparison
     *                 so repricing it to the shared currency is not compared
     *                 against its own old value
     */
    private void requireUniformCurrency(String candidate, ProductVariant ignoring) {
        String normalised = candidate == null || candidate.isBlank()
                ? ProductVariant.DEFAULT_CURRENCY
                : candidate.trim().toUpperCase();

        variants.stream()
                .filter(v -> ignoring == null || !v.id().equals(ignoring.id()))
                .map(ProductVariant::currency)
                .findFirst()
                .filter(existing -> !existing.equals(normalised))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "product '" + slug + "' is priced in " + existing
                                    + "; a variant cannot be priced in " + normalised);
                });
    }

    public void unpublishToDraft() {
        this.status = ProductStatus.DRAFT;
    }

    public void archive() {
        this.status = ProductStatus.ARCHIVED;
    }
}
