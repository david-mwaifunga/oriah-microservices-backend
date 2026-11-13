package com.oriah.catalog.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.model.Category;
import com.oriah.catalog.model.Product;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A category promotion covers "one category with its sub-categories" (round 5), so for each product catalog needs every category it is in AND
 * every category above those: a promotion on Jewellery reaches a ring that is filed under Rings, which sits under Jewellery. The category
 * tree is catalog's own, which is why catalog (not the promotion service) works this out.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class CategoryAncestryTest {

    @Autowired
    private CategoryAncestry ancestry;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private ProductRepository products;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private Category category(String name, Category parent) {
        return categories.saveAndFlush(new Category(name + " " + UUID.randomUUID(), null, parent));
    }

    private UUID productIn(Category... in) {
        Product product = new Product("Piece " + UUID.randomUUID());
        if (in.length > 0) {
            product.assignCategories(Set.of(in), in[0]);
        }
        return products.saveAndFlush(product).id();
    }

    @Test
    @DisplayName("a product's categories come with every category above them: a ring under Rings under Jewellery is covered by a Jewellery promotion")
    void ancestorsAreIncluded() {
        Category jewellery = category("Jewellery", null);
        Category rings = category("Rings", jewellery);
        Category engagement = category("Engagement", rings);
        UUID ring = productIn(engagement);

        Map<UUID, Set<UUID>> answer = ancestry.categoriesWithAncestors(List.of(ring));

        assertThat(answer.get(ring)).containsExactlyInAnyOrder(engagement.id(), rings.id(), jewellery.id());
    }

    @Test
    @DisplayName("a product in two branches is covered by both, a product in no category by none, and every product asked about is answered")
    void severalBranchesAndNoCategory() {
        Category jewellery = category("Jewellery", null);
        Category rings = category("Rings", jewellery);
        Category watches = category("Watches", null);
        Category gifts = category("Gifts", null);
        UUID both = productIn(rings, watches);
        UUID none = productIn();
        UUID gift = productIn(gifts);

        Map<UUID, Set<UUID>> answer = ancestry.categoriesWithAncestors(List.of(both, none, gift));

        assertThat(answer.keySet()).containsExactlyInAnyOrder(both, none, gift);
        assertThat(answer.get(both)).containsExactlyInAnyOrder(rings.id(), jewellery.id(), watches.id());
        assertThat(answer.get(none)).isEmpty();
        assertThat(answer.get(gift)).containsExactly(gifts.id());
    }

    @Test
    @DisplayName("a sibling and a child are not ancestors: a Watches promotion never reaches a ring, and a Rings promotion never reaches a Jewellery-only piece")
    void onlyUpwards() {
        Category jewellery = category("Jewellery", null);
        Category rings = category("Rings", jewellery);
        Category bracelets = category("Bracelets", jewellery);
        UUID inJewelleryOnly = productIn(jewellery);

        Set<UUID> covered = ancestry.categoriesWithAncestors(List.of(inJewelleryOnly)).get(inJewelleryOnly);

        assertThat(covered).containsExactly(jewellery.id()).doesNotContain(rings.id(), bracelets.id());
    }

    @Test
    @DisplayName("nothing asked is nothing answered, with no query")
    void nothingAsked() {
        assertThat(ancestry.categoriesWithAncestors(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a cycle written by hand (bypassing the service) cannot hang it: the walk upward ends")
    void aCycleCannotHangIt() {
        Category a = category("A", null);
        Category b = category("B", a);
        jdbc.update("UPDATE categories SET parent_id = ? WHERE id = ?", b.id(), a.id());
        UUID piece = productIn(b);
        try {
            Set<UUID> covered = ancestry.categoriesWithAncestors(List.of(piece)).get(piece);

            assertThat(covered).containsExactlyInAnyOrder(a.id(), b.id());
        } finally {
            jdbc.update("UPDATE categories SET parent_id = NULL WHERE id = ?", a.id());   // the shared database is left as it was found
        }
    }

    @Test
    @DisplayName("a whole bag's worth of products is answered in one go")
    void manyProducts() {
        Category jewellery = category("Jewellery", null);
        List<UUID> pieces = java.util.stream.IntStream.range(0, 40).mapToObj(i -> productIn(jewellery)).toList();

        Map<UUID, Set<UUID>> answer = ancestry.categoriesWithAncestors(pieces);

        assertThat(answer).hasSize(40);
        assertThat(answer.values()).allSatisfy(set -> assertThat(set).containsExactly(jewellery.id()));
    }
}
