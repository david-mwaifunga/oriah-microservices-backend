package com.oriah.catalog.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.model.Category;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductSort;
import com.oriah.catalog.model.ProductTypeRegistry;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.model.StorefrontFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query behind the product list.
 *
 * <p>Every assertion here is about something the current storefront cannot do:
 * it accepts only {@code page} and {@code tag}, renders no price, and has no
 * concept of a category or a product type.
 *
 * <h2>Why the list is driven from catalog and not from search</h2>
 *
 * search-service holds one row per product with four text fields in a
 * tsvector, no pagination and no facets. Category, type and price are
 * structured data that catalog owns and can index, so filtering belongs here.
 * Full-text stays in search.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ProductStorefrontQueryTest {

    @Autowired
    private ProductRepository products;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private EntityManager entityManager;

    private Category jewellery;
    private Category rings;
    private Category watchesCategory;

    private Product solitaire;   // ACTIVE, jewellery, rings, 4000-5000
    private Product cuff;        // ACTIVE, jewellery, no category, 1200
    private Product diveWatch;   // ACTIVE, watch, watches, 9000
    private Product draftRing;   // DRAFT -- must never appear
    private Product archived;    // ARCHIVED -- must never appear

    @BeforeEach
    void seed() {
        // V3 backfilled the pre-existing demo products, so start from a known
        // state rather than inheriting whatever those are.
        entityManager.createNativeQuery("DELETE FROM product_variant_images").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM product_images").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM product_variants").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM product_categories").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM product_tags").executeUpdate();
        entityManager.createNativeQuery("UPDATE products SET primary_category_id = NULL").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM products").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM categories").executeUpdate();
        entityManager.flush();

        jewellery = categories.save(new Category("Jewellery", null, null));
        rings = categories.save(new Category("Rings", null, jewellery));
        watchesCategory = categories.save(new Category("Watches", null, null));

        solitaire = new Product("Aurelia Solitaire Ring", "A solitaire.", List.of("rings", "18k"));
        solitaire.declareOptionNames(List.of("Ring Size"));
        solitaire.addVariant(new ProductVariant(solitaire, "AUR-S", new BigDecimal("4000.00"), "ZMW",
                Map.of("Ring Size", "S")));
        solitaire.addVariant(new ProductVariant(solitaire, "AUR-L", new BigDecimal("5000.00"), "ZMW",
                Map.of("Ring Size", "L")));
        solitaire.assignCategories(Set.of(rings), rings);
        solitaire.publish();

        cuff = new Product("Minimal Cuff", null, List.of("bracelets"));
        cuff.addVariant(new ProductVariant(cuff, "CUF-1", new BigDecimal("1200.00"), "ZMW", Map.of()));
        cuff.publish();

        diveWatch = new Product("Meridian Dive 300", null, List.of("watches"));
        diveWatch.applyType(ProductTypeRegistry.WATCH, Map.of("movement", "AUTOMATIC"));
        diveWatch.addVariant(new ProductVariant(diveWatch, "MER-1", new BigDecimal("9000.00"), "ZMW", Map.of()));
        diveWatch.assignCategories(Set.of(watchesCategory), watchesCategory);
        diveWatch.publish();

        draftRing = new Product("Unpriced Draft Ring", null, List.of("rings"));
        draftRing.addVariant(new ProductVariant(draftRing, "DRF-1", new BigDecimal("0.00"), "ZMW", Map.of()));
        draftRing.assignCategories(Set.of(rings), rings);

        archived = new Product("Discontinued Band", null, List.of("rings"));
        archived.addVariant(new ProductVariant(archived, "DSC-1", new BigDecimal("2000.00"), "ZMW", Map.of()));
        archived.publish();
        archived.archive();

        products.saveAll(List.of(solitaire, cuff, diveWatch, draftRing, archived));
        entityManager.flush();
        entityManager.clear();
    }

    private List<UUID> idsFor(StorefrontFilter filter) {
        return products.findStorefrontIds(filter, ProductSort.NEWEST, 0, 50);
    }

    @Test
    @DisplayName("only ACTIVE products appear: DRAFT and ARCHIVED are both excluded")
    void onlyActiveProducts() {
        var ids = idsFor(StorefrontFilter.unfiltered());

        assertThat(ids).containsExactlyInAnyOrder(solitaire.id(), cuff.id(), diveWatch.id());
        // The draft is the shape V3 left the whole existing catalogue in, so
        // this is the assertion that keeps the migration from publishing
        // unpriced stock.
        assertThat(ids).doesNotContain(draftRing.id(), archived.id());
    }

    @Test
    @DisplayName("filtering by a parent category returns products in its descendants")
    void categoryFilterIncludesDescendants() {
        // The solitaire is in Rings, not in Jewellery directly. If a parent
        // category did not include descendants it would render an empty grid.
        var descendants = categories.findDescendantIds(jewellery.id());
        assertThat(idsFor(StorefrontFilter.unfiltered().withCategories(descendants)))
                .containsExactly(solitaire.id());

        assertThat(idsFor(StorefrontFilter.unfiltered()
                .withCategories(categories.findDescendantIds(watchesCategory.id()))))
                .containsExactly(diveWatch.id());
    }

    @Test
    @DisplayName("filtering by product type separates watches from jewellery")
    void typeFilter() {
        assertThat(idsFor(StorefrontFilter.unfiltered().withType("WATCH")))
                .containsExactly(diveWatch.id());
        assertThat(idsFor(StorefrontFilter.unfiltered().withType("JEWELLERY")))
                .containsExactlyInAnyOrder(solitaire.id(), cuff.id());
    }

    @Test
    @DisplayName("the existing tag filter still works alongside the new ones")
    void tagFilterStillWorks() {
        assertThat(idsFor(StorefrontFilter.unfiltered().withTag("rings")))
                .containsExactly(solitaire.id());
        assertThat(idsFor(StorefrontFilter.unfiltered().withTag("nonexistent"))).isEmpty();
    }

    @Test
    @DisplayName("price filtering uses the cheapest PURCHASABLE variant, not any variant")
    void priceFilterUsesLowestPurchasablePrice() {
        // solitaire's range is 4000-5000, cuff is 1200, watch is 9000.
        assertThat(idsFor(StorefrontFilter.unfiltered().withPriceRange(null, new BigDecimal("2000"))))
                .containsExactly(cuff.id());
        assertThat(idsFor(StorefrontFilter.unfiltered().withPriceRange(new BigDecimal("3000"), null)))
                .containsExactlyInAnyOrder(solitaire.id(), diveWatch.id());
        assertThat(idsFor(StorefrontFilter.unfiltered()
                .withPriceRange(new BigDecimal("3000"), new BigDecimal("6000"))))
                .containsExactly(solitaire.id());
    }

    @Test
    @DisplayName("a deactivated cheap variant raises the product's effective price")
    void inactiveVariantsAreNotPriced() {
        solitaire = products.findById(solitaire.id()).orElseThrow();
        var cheapest = solitaire.variants().stream()
                .filter(v -> v.sku().equals("AUR-S")).findFirst().orElseThrow();
        solitaire.deactivateVariant(cheapest.id());
        products.save(solitaire);
        entityManager.flush();
        entityManager.clear();

        // Was matched by max 4500; its only buyable option now costs 5000, so
        // matching that filter would advertise a price nobody can pay.
        assertThat(idsFor(StorefrontFilter.unfiltered().withPriceRange(null, new BigDecimal("4500"))))
                .doesNotContain(solitaire.id());
        assertThat(idsFor(StorefrontFilter.unfiltered().withPriceRange(null, new BigDecimal("5000"))))
                .contains(solitaire.id());
    }

    @Test
    @DisplayName("an ACTIVE product whose variants are all unpriced does not appear at all")
    void unpricedActiveProductIsNotListed() {
        // Reachable only by direct SQL, since publish() refuses it -- but the
        // list must not render a card with no price if it ever happens.
        entityManager.createNativeQuery(
                        "UPDATE products SET status = 'ACTIVE' WHERE id = :id")
                .setParameter("id", draftRing.id())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThat(idsFor(StorefrontFilter.unfiltered())).doesNotContain(draftRing.id());
    }

    @Test
    @DisplayName("the price used is the LOWEST purchasable variant, not the highest")
    void priceIsTheMinimumNotTheMaximum() {
        // Without this test, changing MIN to MAX in the query's price
        // expression changes NO assertion in this class -- which an
        // adversarial review demonstrated by making the swap and re-running.
        // The reason was the seed: solitaire's whole 4000-5000 range sat
        // strictly between cuff (1200) and diveWatch (9000), so min and max
        // ordered the three products identically and fell in the same bands.
        //
        // Giving solitaire a 900 variant makes its range STRADDLE cuff's
        // single price, so min and max now disagree about both the ordering
        // and the band.
        solitaire = products.findById(solitaire.id()).orElseThrow();
        solitaire.addVariant(new ProductVariant(solitaire, "AUR-XS", new BigDecimal("900.00"),
                "ZMW", Map.of("Ring Size", "XS")));
        products.save(solitaire);
        entityManager.flush();
        entityManager.clear();

        // By MIN, solitaire is now the cheapest product (900 < 1200).
        // By MAX it would be the dearest of the two (5000 > 1200).
        assertThat(products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_ASC, 0, 50))
                .containsExactly(solitaire.id(), cuff.id(), diveWatch.id());

        // By MIN solitaire matches a band cuff does not; by MAX neither would.
        assertThat(idsFor(StorefrontFilter.unfiltered().withPriceRange(null, new BigDecimal("1000"))))
                .containsExactly(solitaire.id());
    }

    @Test
    @DisplayName("a zero-priced variant on a LIVE product does not drag its price down to zero")
    void zeroPricedVariantIsNotTheProductsPrice() {
        // The `unit_price > 0` condition appears TWICE in the query -- once in
        // the EXISTS that decides whether a product is listable at all, and
        // once in the expression that computes its price. Only the first was
        // covered: the single zero-priced variant in the seed belonged to a
        // DRAFT product, so deleting `> 0` from the price expression broke
        // nothing. This is the reachable case, since a published product can
        // be given an unpriced variant afterwards.
        solitaire = products.findById(solitaire.id()).orElseThrow();
        solitaire.addVariant(new ProductVariant(solitaire, "AUR-FREE", new BigDecimal("0.00"),
                "ZMW", Map.of("Ring Size", "XS")));
        products.save(solitaire);
        entityManager.flush();
        entityManager.clear();

        // PRECONDITION, asserted rather than assumed. The first version of
        // this test passed even with `unit_price > 0` deleted from the price
        // expression -- the mutation it was written to catch -- because the
        // 0.00 variant was not actually reaching the database, so there was
        // no zero for the query to pick up and nothing to get wrong. A test
        // whose setup silently fails passes for the wrong reason, which is
        // indistinguishable from passing for the right one.
        assertThat(products.findById(solitaire.id()).orElseThrow().variants())
                .as("the 0.00 variant must actually be persisted, or this test proves nothing")
                .hasSize(3)
                .anySatisfy(v -> assertThat(v.unitPrice()).isEqualByComparingTo("0.00"));

        // Zero is V3's marker for "nobody has priced this yet", not a price.
        // Were it treated as one, this product would advertise "from K0.00"
        // and match a band nobody can buy in.
        assertThat(idsFor(StorefrontFilter.unfiltered().withPriceRange(null, new BigDecimal("1"))))
                .isEmpty();
        assertThat(products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_ASC, 0, 50))
                .containsExactly(cuff.id(), solitaire.id(), diveWatch.id());
    }

    @Test
    @DisplayName("sorting by price ascending and descending uses the lowest purchasable price")
    void sortByPrice() {
        var asc = products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_ASC, 0, 50);
        assertThat(asc).containsExactly(cuff.id(), solitaire.id(), diveWatch.id());

        var desc = products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_DESC, 0, 50);
        assertThat(desc).containsExactly(diveWatch.id(), solitaire.id(), cuff.id());
    }

    @Test
    @DisplayName("sorting by title is alphabetical and stable")
    void sortByTitle() {
        assertThat(products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.TITLE_ASC, 0, 50))
                .containsExactly(solitaire.id(), diveWatch.id(), cuff.id());
    }

    @Test
    @DisplayName("paging returns a stable window and the count ignores it")
    void pagingIsStable() {
        var firstPage = products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_ASC, 0, 2);
        var secondPage = products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_ASC, 1, 2);

        assertThat(firstPage).containsExactly(cuff.id(), solitaire.id());
        assertThat(secondPage).containsExactly(diveWatch.id());
        // The count is what the UI paginates on, so it must be the unpaged
        // total rather than the size of the window.
        assertThat(products.countStorefront(StorefrontFilter.unfiltered())).isEqualTo(3);
        assertThat(products.countStorefront(StorefrontFilter.unfiltered().withType("WATCH"))).isEqualTo(1);
    }

    @Test
    @DisplayName("filters combine rather than replacing each other")
    void filtersCombine() {
        var filter = StorefrontFilter.unfiltered()
                .withType("JEWELLERY")
                .withPriceRange(new BigDecimal("3000"), null)
                .withCategories(categories.findDescendantIds(jewellery.id()));

        assertThat(idsFor(filter)).containsExactly(solitaire.id());

        // Same filter, contradictory price band -> nothing, not everything.
        assertThat(idsFor(filter.withPriceRange(new BigDecimal("100"), new BigDecimal("200")))).isEmpty();
    }

    @Test
    @DisplayName("an empty category id list means no match, not 'no filter'")
    void emptyCategoryListMatchesNothing() {
        // findDescendantIds for an unknown category returns empty. Treating
        // that as "no filter" would turn a bad category slug into "here is the
        // entire catalogue", which looks like it worked.
        assertThat(idsFor(StorefrontFilter.unfiltered().withCategories(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("loading the listed products brings their variants, images and categories without a lazy-load failure")
    void detailsLoadForTheListedIds() {
        var ids = products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_ASC, 0, 50);
        entityManager.clear();

        var loaded = products.findAllWithDetailByIdIn(ids);
        assertThat(loaded).hasSize(3);

        // DETACHED before the collections are read, and that is the whole
        // point of this assertion. @DataJpaTest runs inside a transaction with
        // an open session, so simply touching a lazy collection here would
        // succeed whatever the fetch strategy was -- the test would pin the
        // data and prove nothing about eagerness. Detaching severs the
        // session, so anything not already initialised throws
        // LazyInitializationException, which is exactly the failure this
        // codebase has shipped once before (a service returning entities that
        // the controller then mapped outside the transaction).
        loaded.forEach(entityManager::detach);

        assertThat(loaded).allSatisfy(p -> {
            assertThat(p.variants()).isNotEmpty();
            assertThat(p.categories()).isNotNull();
            assertThat(p.images()).isNotNull();
            assertThat(p.lowestActivePrice()).isPresent();
            assertThat(p.currency()).contains("ZMW");
        });
    }

    @Test
    @DisplayName("the listed order survives the second query, which the database does not preserve")
    void orderSurvivesTheDetailLoad() {
        var ids = products.findStorefrontIds(
                StorefrontFilter.unfiltered(), ProductSort.PRICE_ASC, 0, 50);
        assertThat(ids).containsExactly(cuff.id(), solitaire.id(), diveWatch.id());

        // An IN list does not imply an ORDER BY, so without an explicit
        // re-sort the chosen ordering would be computed and then thrown away.
        assertThat(products.findAllWithDetailByIdIn(ids))
                .extracting(Product::id)
                .containsExactly(cuff.id(), solitaire.id(), diveWatch.id());
    }
}
