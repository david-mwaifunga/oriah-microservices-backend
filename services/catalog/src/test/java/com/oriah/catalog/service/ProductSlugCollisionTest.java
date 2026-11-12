package com.oriah.catalog.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Two products whose titles reduce to the same slug.
 *
 * <h2>Why this test exists</h2>
 *
 * {@code products.slug} is {@code NOT NULL UNIQUE}, and the product
 * constructor derives it from the title unconditionally. The comment on that
 * constructor claimed "the service resolves collisions, since the column is
 * UNIQUE" — which was simply false: {@code assignSlug} had exactly one
 * occurrence in the whole repository, its own declaration, with no caller.
 *
 * <p>So a second product with the same title violated the constraint, and
 * because nothing in the platform handled {@code DataIntegrityViolationException}
 * it fell through to the catch-all handler and surfaced as a <strong>500</strong>.
 * An admin creating "Aurelia Ring" twice — or "Aurelia Ring" and then
 * "Aurelia-Ring", or "AURELIA RING", all of which reduce to {@code aurelia-ring}
 * — got an opaque server error for an ordinary mistake.
 *
 * <p>Uses a real Postgres rather than a mock, because the constraint is the
 * thing under test: a mocked repository would accept both saves and prove
 * nothing.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ProductSlugCollisionTest {

    /** Who the event log records as making these changes (ProductOutboxTest covers the actor). */
    private static final String STAFF = "user:catalog-test";

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        // deleteAll rather than native queries: @SpringBootTest is not
        // transactional, so an EntityManager write throws
        // TransactionRequiredException. Spring Data manages its own.
        repository.deleteAll();
    }

    @Test
    @DisplayName("the same title twice succeeds, with distinct slugs")
    void identicalTitlesGetDistinctSlugs() {
        var first = service.create("Aurelia Solitaire Ring", null, List.of(), STAFF);
        var second = service.create("Aurelia Solitaire Ring", null, List.of(), STAFF);

        assertThat(first.slug()).isEqualTo("aurelia-solitaire-ring");
        assertThat(second.slug())
                .startsWith("aurelia-solitaire-ring-")
                .isNotEqualTo(first.slug());
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("two creates of one title at the same moment both succeed, with distinct slugs and an event each")
    void concurrentCreatesOfOneTitleBothSucceed() throws Exception {
        // The race the slug check alone cannot see: the first create has
        // inserted "aurelia-solitaire-ring" but not committed, so the second
        // looks, finds it free, and takes it too. The test holds the first
        // open until Postgres reports the second waiting for a lock, so the
        // second has done all it can before the first commits.
        ExecutorService staff = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch firstCreated = new CountDownLatch(1);
            CountDownLatch firstMayCommit = new CountDownLatch(1);

            Future<ProductResponse> first = staff.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                ProductResponse created = service.create("Aurelia Solitaire Ring", null, List.of(), STAFF);
                firstCreated.countDown();
                awaitQuietly(firstMayCommit);
                return created;
            }));
            assertThat(firstCreated.await(10, TimeUnit.SECONDS)).as("the first create applied").isTrue();

            Future<ProductResponse> second = staff.submit(() ->
                    service.create("Aurelia Solitaire Ring", null, List.of(), STAFF));
            awaitABlockedSession();
            firstMayCommit.countDown();

            ProductResponse one = first.get(10, TimeUnit.SECONDS);
            ProductResponse two = second.get(10, TimeUnit.SECONDS);

            assertThat(one.slug()).isEqualTo("aurelia-solitaire-ring");
            assertThat(two.slug()).startsWith("aurelia-solitaire-ring-").isNotEqualTo(one.slug());
            assertThat(repository.count()).isEqualTo(2);
            // Each create committed whole: the product and its product.created.
            for (ProductResponse created : List.of(one, two)) {
                assertThat(jdbc.queryForList(
                        "SELECT event_type FROM outbox WHERE aggregate_type = 'product' AND aggregate_id = ?",
                        String.class, created.id().toString()))
                        .containsExactly("product.created");
            }
        } finally {
            staff.shutdownNow();
        }
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
        throw new AssertionError("the second create never waited for the first: nothing was blocked within 10s");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("titles differing only in punctuation or case collide and are still both accepted")
    void punctuationEquivalentTitlesCollide() {
        // All three reduce to "aurelia-ring" through Slug.from, which is the
        // non-obvious part: an admin would not expect these to clash.
        service.create("Aurelia Ring", null, List.of(), STAFF);
        assertThatCode(() -> service.create("Aurelia-Ring", null, List.of(), STAFF))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.create("AURELIA RING", null, List.of(), STAFF))
                .doesNotThrowAnyException();

        assertThat(repository.findAll()).extracting("slug").doesNotHaveDuplicates();
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("titles that strip to nothing collide on the fallback and are still both accepted")
    void punctuationOnlyTitlesCollide() {
        // Slug.from returns the literal "product" for a title with no
        // alphanumerics, so every such title collides with every other.
        service.create("!!!", null, List.of(), STAFF);
        assertThatCode(() -> service.create("???", null, List.of(), STAFF))
                .doesNotThrowAnyException();

        assertThat(repository.findAll()).extracting("slug").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a slug colliding with one the migration backfilled is also resolved")
    void collidesWithAMigratedSlug() {
        // V3 backfilled slugs for pre-existing products using the same rules,
        // so a newly created product can collide with one it has never seen.
        jdbc.execute("""
                INSERT INTO products (id, title, slug, status, product_type_code,
                                      attributes, option_names, is_deleted, kind)
                VALUES (gen_random_uuid(), 'Legacy Piece', 'legacy-piece', 'DRAFT',
                        'JEWELLERY', '{}'::jsonb, '{}', false, 'SELLABLE')
                """);

        var created = service.create("Legacy Piece", null, List.of(), STAFF);

        assertThat(created.slug()).isNotEqualTo("legacy-piece").startsWith("legacy-piece-");
    }

    @Test
    @DisplayName("the first product with a title keeps the clean slug, so URLs are not gratuitously ugly")
    void firstOneKeepsTheCleanSlug() {
        var only = service.create("Meridian Dive 300", null, List.of(), STAFF);
        assertThat(only.slug()).isEqualTo("meridian-dive-300");
    }
}
