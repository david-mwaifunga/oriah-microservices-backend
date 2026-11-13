package com.oriah.catalog.events;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.exception.PricingProblem;
import com.oriah.catalog.pricing.GuardResult;
import com.oriah.catalog.pricing.HeldCost;
import com.oriah.catalog.pricing.PriceGuard;
import com.oriah.catalog.pricing.PricingFixtures;
import com.oriah.catalog.service.PriceCheck;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.catalog.model.Category;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.repository.CategoryRepository;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.catalog.service.CategoryService;
import com.oriah.catalog.service.ProductService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two members of staff change one product at the same moment. Both changes
 * must stick, and the event recorded last must show the product as it now
 * is -- because a consumer (search, inventory) keeps only the newest snapshot
 * of a product, and would otherwise go on showing a price, a title or a
 * status the database no longer holds. And once a product is deleted,
 * nothing may be recorded after its product.deleted, or a consumer would put
 * it back.
 *
 * <h2>How it went wrong without a lock</h2>
 *
 * Each write read the product, changed it and recorded a snapshot of the
 * whole product. The second read the product BEFORE the first committed, so
 * its snapshot missed the first change; the outbox numbered it after the
 * first, so every consumer applied it last and kept the stale picture. A
 * write that read a product a moment before a delete committed still saw it
 * alive, went ahead, and recorded a product.updated after the
 * product.deleted -- and, writing back every column it had read, even set
 * the row's deleted flag back to false.
 *
 * <h2>How the test makes that happen every time</h2>
 *
 * The first write runs inside a transaction the test holds open after the
 * change. The second then starts, and the test waits until Postgres reports
 * it blocked on a lock (pg_stat_activity) before letting the first commit:
 * so the second has done everything it can do before the first commits.
 * With the product row locked (SELECT ... FOR UPDATE) as each write's first
 * step, what it can do is nothing -- it waits at the read, and then reads the
 * product with the first change already in it.
 *
 * <p>Each case is the lock of the SECOND write: replace that write's
 * {@code findByIdForUpdate} with a plain {@code findById} and its cases fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConcurrentProductWritesTest {

    private static final String STAFF_A = "user:staff-a";
    private static final String STAFF_B = "user:staff-b";

    /** The ring every case starts from: two sizes, in Kwacha, still a draft. */
    private static final String TITLE = "Aurelia Solitaire Ring";
    private static final BigDecimal SMALL_PRICE = new BigDecimal("4000.00");
    private static final BigDecimal LARGE_PRICE = new BigDecimal("5000.00");

    @Autowired
    private ProductService products;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final ExecutorService staff = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopStaff() {
        staff.shutdownNow();
    }

    /** A second write, and the mark it must leave on the snapshot recorded last. */
    record Change(BiFunction<ProductService, Ring, Object> apply, BiConsumer<JsonNode, Ring> seenIn) {
    }

    static Stream<Arguments> writesThatRecordASnapshot() {
        return Stream.of(
                Arguments.of("a reprice of the other size", new Change(
                        (products, ring) -> reprice(products, ring, ring.large(), "5200.00", STAFF_B),
                        (snapshot, ring) -> assertThat(pricesBySku(snapshot).get(ring.largeSku()))
                                .as("the second reprice, in the later snapshot").isEqualByComparingTo("5200.00"))),
                Arguments.of("a new size", new Change(
                        (products, ring) -> products.addVariant(ring.id(), new VariantCreateRequest(
                                ring.extraSku(), null, "ZMW", Map.of("Ring Size", "XL")), STAFF_B),
                        (snapshot, ring) -> assertThat(pricesBySku(snapshot).get(ring.extraSku()))
                                .as("the new size (unpriced), in the later snapshot").isEqualByComparingTo("0.00"))),
                Arguments.of("publishing it", new Change(
                        (products, ring) -> products.publish(ring.id(), STAFF_B),
                        (snapshot, ring) -> assertThat(snapshot.get("status").asText())
                                .as("the publication, in the later snapshot").isEqualTo("ACTIVE"))),
                Arguments.of("a rename", new Change(
                        (products, ring) -> products.update(ring.id(),
                                ProductUpdateRequest.describing("Aurelia Ring", null, null), STAFF_B),
                        (snapshot, ring) -> assertThat(snapshot.get("title").asText())
                                .as("the rename, in the later snapshot").isEqualTo("Aurelia Ring"))));
    }

    @ParameterizedTest(name = "{0}, made while a reprice is in flight, waits for it, and the later snapshot carries both")
    @MethodSource("writesThatRecordASnapshot")
    void aWriteWaitsForAConcurrentRepriceAndTheLaterSnapshotCarriesBoth(String write, Change second) throws Exception {
        Ring ring = aRing();

        secondWaitsForFirst(
                () -> reprice(products, ring, ring.small(), "4100.00", STAFF_A),
                () -> second.apply().apply(products, ring));

        assertThat(price(ring.small())).as("the first reprice persisted").isEqualByComparingTo("4100.00");

        List<Map<String, Object>> rows = rows(ring.id());
        assertThat(rows).extracting(row -> row.get("actor")).containsExactly(STAFF_A, STAFF_B);

        // The later snapshot is the one every consumer keeps.
        JsonNode later = objectMapper.readTree((String) rows.get(1).get("payload"));
        assertThat(pricesBySku(later).get(ring.smallSku()))
                .as("the first reprice, in the later snapshot").isEqualByComparingTo("4100.00");
        second.seenIn().accept(later, ring);
        assertThatTheSnapshotIsTheDatabase(later, ring.id());
    }

    static Stream<Arguments> writesAfterADelete() {
        return Stream.of(
                Arguments.of("a reprice", (BiFunction<ProductService, Ring, Object>) (products, ring) -> {
                    try {
                        return reprice(products, ring, ring.small(), "1.00", STAFF_B);
                    } catch (ResourceNotFoundException gone) {
                        // The pricing write answers a deleted product with a 404, where the others answer empty: the same finding.
                        return Optional.empty();
                    }
                }),
                Arguments.of("a new size", (BiFunction<ProductService, Ring, Object>) (products, ring) ->
                        products.addVariant(ring.id(), new VariantCreateRequest(
                                ring.extraSku(), null, "ZMW", Map.of("Ring Size", "XL")), STAFF_B)),
                Arguments.of("publishing it", (BiFunction<ProductService, Ring, Object>) (products, ring) ->
                        products.publish(ring.id(), STAFF_B)),
                Arguments.of("a rename", (BiFunction<ProductService, Ring, Object>) (products, ring) ->
                        products.update(ring.id(), ProductUpdateRequest.describing("Aurelia Ring", null, null), STAFF_B)),
                Arguments.of("a second delete", (BiFunction<ProductService, Ring, Object>) (products, ring) ->
                        products.deleteById(ring.id(), STAFF_B)));
    }

    @ParameterizedTest(name = "{0}, made while a delete is in flight, waits for it, finds the product gone and records nothing")
    @MethodSource("writesAfterADelete")
    void aWriteThatWaitsForADeleteFindsTheProductGone(String write, BiFunction<ProductService, Ring, Object> second)
            throws Exception {
        Ring ring = aRing();

        Object result = secondWaitsForFirst(
                () -> products.deleteById(ring.id(), STAFF_A),
                () -> second.apply(products, ring));

        // Empty, so the controller answers 404 (false for a delete: 404 too).
        assertThat(result).as("the second write found nothing to change").isIn(Optional.empty(), Boolean.FALSE);

        // product.deleted stays the last word: nothing is numbered after it.
        assertThat(rows(ring.id()))
                .extracting(row -> row.get("event_type") + " by " + row.get("actor"))
                .containsExactly("product.deleted by " + STAFF_A);

        // And the row is as the delete left it: still deleted, still archived,
        // nothing written back over it.
        assertThat(jdbc.queryForMap("SELECT is_deleted, status, title FROM products WHERE id = ?", ring.id()))
                .containsEntry("is_deleted", true)
                .containsEntry("status", "ARCHIVED")
                .containsEntry("title", TITLE);
        assertThat(pricesInTheDatabase(ring.id())).containsOnlyKeys(ring.smallSku(), ring.largeSku());
        assertThat(price(ring.small())).isEqualByComparingTo(SMALL_PRICE);
        assertThat(price(ring.large())).isEqualByComparingTo(LARGE_PRICE);
    }

    // ------------------------------------------- a box and the products that go out in it

    @Test
    @DisplayName("choosing a box, made while the box's own product is being deleted, waits for the delete, finds the box gone and is refused: nothing is assigned")
    void choosingABoxWaitsForItsDeleteAndFindsItGone() throws Exception {
        Ring ring = aRing();
        PricingFixtures.Piece box = new PricingFixtures(repository).box("Gift box " + ring.suffix());

        Object outcome = secondWaitsForFirst(
                () -> products.deleteById(box.productId(), STAFF_A),
                () -> {
                    try {
                        products.chooseDefaultPackaging(ring.id(), box.variantId(), STAFF_B);
                        return "assigned";
                    } catch (IllegalArgumentException refused) {
                        return "refused";
                    }
                });

        assertThat(outcome).as("the choice waited for the delete and then saw a box that is gone").isEqualTo("refused");
        assertThat(jdbc.queryForObject("SELECT default_packaging_variant_id FROM products WHERE id = ?", UUID.class, ring.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM products WHERE id = ?", Boolean.class, box.productId())).isTrue();
    }

    @Test
    @DisplayName("deleting a box, made while a product is choosing it, waits for the choice, sees the box in use and is refused: the box survives and the product keeps it")
    void deletingABoxWaitsForAChoiceAndSeesItInUse() throws Exception {
        Ring ring = aRing();
        PricingFixtures.Piece box = new PricingFixtures(repository).box("Gift box " + ring.suffix());

        Object outcome = secondWaitsForFirst(
                () -> products.chooseDefaultPackaging(ring.id(), box.variantId(), STAFF_A),
                () -> {
                    try {
                        products.deleteById(box.productId(), STAFF_B);
                        return "deleted";
                    } catch (PricingProblem inUse) {
                        return inUse.code();
                    }
                });

        assertThat(outcome).as("the delete waited for the choice and then saw the box in use").isEqualTo("packaging-in-use");
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM products WHERE id = ?", Boolean.class, box.productId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT default_packaging_variant_id FROM products WHERE id = ?", UUID.class, ring.id())).isEqualTo(box.variantId());
    }

    @Test
    @DisplayName("a packaging entry that tries to choose a box is refused at once, without waiting on the box it named: two of them naming each other could otherwise wait for each other")
    void aPackagingEntryIsRefusedBeforeAnyBoxIsLocked() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        PricingFixtures fixtures = new PricingFixtures(repository);
        PricingFixtures.Piece one = fixtures.box("Box one " + suffix);
        PricingFixtures.Piece two = fixtures.box("Box two " + suffix);

        assertRefusedAtOnceWhileLocked(two.productId(), () -> {
            try {
                products.chooseDefaultPackaging(one.productId(), two.variantId(), STAFF_B);
                return "chosen";
            } catch (IllegalStateException refused) {
                return "refused";
            }
        });
    }

    @Test
    @DisplayName("naming a variant of a piece that is not packaging is refused at once, without waiting on that piece's lock: two products naming each other's variants could otherwise wait for each other")
    void aVariantThatIsNotPackagingIsRefusedBeforeItsProductIsLocked() throws Exception {
        Ring one = aRing();
        Ring other = aRing();

        assertRefusedAtOnceWhileLocked(other.id(), () -> {
            try {
                products.chooseDefaultPackaging(one.id(), other.small(), STAFF_B);
                return "chosen";
            } catch (IllegalArgumentException refused) {
                return "refused";
            }
        });
    }

    /**
     * Locks a product in another transaction, runs {@code attempt}, and requires its answer "refused" within 5 s, i.e. without waiting on
     * that lock. A TimeoutException means it queued behind the lock, which two writes naming each other's products would do forever.
     */
    private void assertRefusedAtOnceWhileLocked(UUID lockedProductId, Callable<String> attempt) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = staff.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            repository.findByIdForUpdate(lockedProductId);   // the product it names, locked by someone else
            held.countDown();
            awaitQuietly(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).as("the other product is locked").isTrue();
        try {
            assertThat(staff.submit(attempt).get(5, TimeUnit.SECONDS)).isEqualTo("refused");
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------ deleting a category while a product that uses it is written

    @Test
    @DisplayName("deleting a category, made while another write to a product whose primary category it is, waits for that write and does not undo it: the product keeps the change and only loses the category")
    void aCategoryDeleteDoesNotUndoAConcurrentWrite() throws Exception {
        Ring ring = aRing();
        Category rings = categoryRepository.save(new Category("Rings " + ring.suffix(), null, null));
        jdbc.update("INSERT INTO product_categories (product_id, category_id) VALUES (?, ?)", ring.id(), rings.id());
        jdbc.update("UPDATE products SET primary_category_id = ? WHERE id = ?", rings.id(), ring.id());

        Object deleted = secondWaitsForFirst(
                () -> products.publish(ring.id(), STAFF_A),
                () -> categoryService.delete(rings.id()));

        assertThat(deleted).isEqualTo(true);
        assertThat(jdbc.queryForMap("SELECT status, primary_category_id FROM products WHERE id = ?", ring.id()))
                .as("the publish that was in flight survives the category delete, and only the category is gone from the product")
                .containsEntry("status", "ACTIVE")
                .containsEntry("primary_category_id", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?", Long.class, rings.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_categories WHERE product_id = ?", Long.class, ring.id())).isZero();
    }

    // ------------------------------------------------------------- helpers

    /**
     * A reprice, as the pricing endpoint makes one once it has looked at the cost: no stock is held in these cases, so no cost is known.
     * The ring's sizes were saved past the service with prices and no history, so no earlier price is expected.
     */
    private static Object reprice(ProductService products, Ring ring, UUID variant, String price, String actor) {
        HeldCost nothingHeld = new HeldCost(variant, 0, 0, null, null, null, false, List.of());
        PriceCheck.Reading reading = new PriceCheck.Reading(variant, null, nothingHeld, null, java.time.Instant.now());
        GuardResult result = PriceGuard.evaluate(new BigDecimal(price), "ZMW", nothingHeld, null, null);
        return products.setVariantPrice(ring.id(), new ProductService.PriceWrite(variant, new BigDecimal(price), "ZMW", null, null, null,
                result, reading, null, null), actor);
    }

    /** The ring, its two sizes, and the SKUs its cases use (SKUs are unique catalogue-wide). */
    record Ring(UUID id, UUID small, UUID large, String suffix) {
        String smallSku() {
            return "AUR-S-" + suffix;
        }

        String largeSku() {
            return "AUR-L-" + suffix;
        }

        String extraSku() {
            return "AUR-XL-" + suffix;
        }
    }

    /** A draft ring with two priced sizes, saved directly: it has no events of its own. */
    private Ring aRing() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Product ring = new Product(TITLE, null, List.of("rings"));
        // Saved past the service, so its slug is made unique here: slugs are
        // unique, and every case uses a ring of the same title.
        ring.assignSlug("aurelia-solitaire-ring-" + suffix);
        ring.declareOptionNames(List.of("Ring Size"));
        ProductVariant small = ring.addVariant(new ProductVariant(ring, "AUR-S-" + suffix,
                SMALL_PRICE, "ZMW", Map.of("Ring Size", "S")));
        ProductVariant large = ring.addVariant(new ProductVariant(ring, "AUR-L-" + suffix,
                LARGE_PRICE, "ZMW", Map.of("Ring Size", "L")));
        repository.saveAndFlush(ring);
        return new Ring(ring.id(), small.id(), large.id(), suffix);
    }

    /**
     * Runs {@code first} in a transaction held open after its change, starts
     * {@code second}, and lets the first commit only once the second is
     * waiting for a lock. Returns what the second returned.
     */
    private <T> T secondWaitsForFirst(Runnable first, Callable<T> second) throws Exception {
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);

        Future<?> one = staff.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            first.run();
            firstApplied.countDown();
            awaitQuietly(firstMayCommit);
        }));
        if (!firstApplied.await(10, TimeUnit.SECONDS)) {
            one.get(1, TimeUnit.SECONDS); // surfaces the first write's own failure, if it had one
            throw new AssertionError("the first write never applied");
        }

        Future<T> two = staff.submit(second);
        awaitABlockedSession();
        firstMayCommit.countDown();

        one.get(10, TimeUnit.SECONDS);
        return two.get(10, TimeUnit.SECONDS);
    }

    /** Waits until some session in this database is waiting for a lock. */
    private void awaitABlockedSession() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Long blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'""", Long.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the second write never waited for the first: nothing was blocked within 10s");
    }

    /**
     * The snapshot recorded last is the product as the database now holds
     * it: its title, its status, and every size at its price.
     */
    private void assertThatTheSnapshotIsTheDatabase(JsonNode snapshot, UUID productId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT title, status FROM products WHERE id = ?", productId);
        assertThat(snapshot.get("title").asText()).isEqualTo(row.get("title"));
        assertThat(snapshot.get("status").asText()).isEqualTo(row.get("status"));

        Map<String, BigDecimal> recorded = pricesBySku(snapshot);
        Map<String, BigDecimal> held = pricesInTheDatabase(productId);
        assertThat(recorded).containsOnlyKeys(held.keySet());
        held.forEach((sku, price) -> assertThat(recorded.get(sku)).as(sku).isEqualByComparingTo(price));
    }

    private List<Map<String, Object>> rows(UUID productId) {
        return jdbc.queryForList("""
                SELECT seq, event_type, actor, payload::text AS payload FROM outbox
                 WHERE aggregate_type = 'product' AND aggregate_id = ?
                 ORDER BY seq""", productId.toString());
    }

    private BigDecimal price(UUID variantId) {
        return jdbc.queryForObject("SELECT unit_price FROM product_variants WHERE id = ?", BigDecimal.class, variantId);
    }

    private Map<String, BigDecimal> pricesInTheDatabase(UUID productId) {
        Map<String, BigDecimal> prices = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT sku, unit_price FROM product_variants WHERE product_id = ?",
                rs -> {
                    prices.put(rs.getString("sku"), rs.getBigDecimal("unit_price"));
                },
                productId);
        return prices;
    }

    /** Each variant's unitPrice in the snapshot, by SKU (the format itself is pinned by the golden test). */
    private static Map<String, BigDecimal> pricesBySku(JsonNode snapshot) {
        Map<String, BigDecimal> prices = new java.util.LinkedHashMap<>();
        snapshot.get("variants").forEach(v -> prices.put(v.get("sku").asText(), new BigDecimal(v.get("unitPrice").asText())));
        return prices;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
