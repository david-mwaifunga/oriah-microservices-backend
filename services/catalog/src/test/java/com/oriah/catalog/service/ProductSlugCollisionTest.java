package com.oriah.catalog.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.oriah.catalog.TestcontainersConfiguration;
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
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ProductSlugCollisionTest {

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

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
        var first = service.create("Aurelia Solitaire Ring", null, List.of());
        var second = service.create("Aurelia Solitaire Ring", null, List.of());

        assertThat(first.slug()).isEqualTo("aurelia-solitaire-ring");
        assertThat(second.slug())
                .startsWith("aurelia-solitaire-ring-")
                .isNotEqualTo(first.slug());
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("titles differing only in punctuation or case collide and are still both accepted")
    void punctuationEquivalentTitlesCollide() {
        // All three reduce to "aurelia-ring" through Slug.from, which is the
        // non-obvious part: an admin would not expect these to clash.
        service.create("Aurelia Ring", null, List.of());
        assertThatCode(() -> service.create("Aurelia-Ring", null, List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.create("AURELIA RING", null, List.of()))
                .doesNotThrowAnyException();

        assertThat(repository.findAll()).extracting("slug").doesNotHaveDuplicates();
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("titles that strip to nothing collide on the fallback and are still both accepted")
    void punctuationOnlyTitlesCollide() {
        // Slug.from returns the literal "product" for a title with no
        // alphanumerics, so every such title collides with every other.
        service.create("!!!", null, List.of());
        assertThatCode(() -> service.create("???", null, List.of()))
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
                                      attributes, option_names, is_deleted)
                VALUES (gen_random_uuid(), 'Legacy Piece', 'legacy-piece', 'DRAFT',
                        'JEWELLERY', '{}'::jsonb, '{}', false)
                """);

        var created = service.create("Legacy Piece", null, List.of());

        assertThat(created.slug()).isNotEqualTo("legacy-piece").startsWith("legacy-piece-");
    }

    @Test
    @DisplayName("the first product with a title keeps the clean slug, so URLs are not gratuitously ugly")
    void firstOneKeepsTheCleanSlug() {
        var only = service.create("Meridian Dive 300", null, List.of());
        assertThat(only.slug()).isEqualTo("meridian-dive-300");
    }
}
