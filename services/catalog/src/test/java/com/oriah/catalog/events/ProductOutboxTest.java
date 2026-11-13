package com.oriah.catalog.events;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;

import static com.oriah.catalog.events.ProductEventGoldenTest.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every product and price change is written to catalog's outbox, once, in
 * the transaction that makes it (MVP_PLAN Phase 1, item 4).
 *
 * <h2>Why in the same transaction</h2>
 *
 * Before the outbox, catalog sent its Kafka message after the commit. A crash
 * between the two lost the message for good: inventory never heard of a new
 * variant, search kept an old price. Written in the same transaction, the
 * fact and the change commit together or not at all: there is never a price
 * change the log does not hold, nor a logged change that rolled back.
 *
 * <p>Each row is a full snapshot of the product (except product.deleted,
 * which carries only the id, as it always has), numbered 1, 2, 3 within the
 * product, so a consumer can skip an older snapshot that arrives late. The
 * stored payload is today's wire format byte for byte -- the same golden
 * files the published messages are held to.
 *
 * <p>The outbox is append-only, so nothing here can be cleaned up between
 * tests: each test works on products it creates and reads only their rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProductOutboxTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private AdminWrites admin;

    @BeforeEach
    void signIn() {
        admin = new AdminWrites(mvc, objectMapper, "outbox-admin");
    }

    @Test
    @DisplayName("each admin write appends exactly one snapshot: in order, as the caller, in today's wire format")
    void eachWriteAppendsOneSnapshot() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        String sku = freshSku();
        String productId = admin.create("{\"title\":\"Aurelia Solitaire Ring\",\"tags\":[\"rings\",\"gold\"]}");
        assertThat(rows(productId)).hasSize(1);

        admin.addVariant(productId, "{\"sku\":\"" + sku + "\"}").andExpect(status().isCreated());
        assertThat(rows(productId)).hasSize(2);
        String variantId = admin.variantId(productId, 0);

        admin.price(variantId, "{\"unitPrice\":4600.50,\"expectedPriceId\":null}").andExpect(status().isOk());
        assertThat(rows(productId)).hasSize(3);

        admin.publish(productId).andExpect(status().isOk());
        assertThat(rows(productId)).hasSize(4);

        admin.update(productId, "{\"title\":\"Aurelia Ring\"}").andExpect(status().isOk());
        assertThat(rows(productId)).hasSize(5);

        admin.remove(productId).andExpect(status().isNoContent());
        Instant after = Instant.now();

        List<Map<String, Object>> rows = rows(productId);
        assertThat(rows).extracting(row -> row.get("seq")).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(rows).extracting(row -> row.get("event_type")).containsExactly(
                "product.created", "product.updated", "product.updated",
                "product.updated", "product.updated", "product.deleted");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("topic")).isEqualTo("catalog.product");
            assertThat(row.get("schema_version")).isEqualTo(1);
            // The person whose token made the change, not the service.
            assertThat(row.get("actor")).isEqualTo("user:outbox-admin");
            assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isBetween(before, after);
        });

        Map<String, String> ids = Map.of("productId", productId, "variantId", variantId);
        assertThat(rows).extracting(row -> row.get("payload")).containsExactly(
                golden("product.created.json", ids),
                golden("product.updated.variant-added.json", ids).replace(GOLDEN_SKU, goldenSku(sku)),
                golden("product.updated.repriced.json", ids).replace(GOLDEN_SKU, goldenSku(sku)),
                golden("product.updated.published.json", ids).replace(GOLDEN_SKU, goldenSku(sku)),
                golden("product.updated.renamed.json", ids).replace(GOLDEN_SKU, goldenSku(sku)),
                golden("product.deleted.json", ids));
    }

    @Test
    @DisplayName("a refused write appends nothing, and neither does a write to a product that does not exist")
    void refusedWritesAppendNothing() throws Exception {
        String productId = admin.create("{\"title\":\"Unpriced Piece\",\"tags\":[]}");

        // No variant priced above zero: the aggregate refuses to publish
        // (409) inside the service's transaction, which rolls back, so the
        // log still holds the create alone.
        admin.publish(productId).andExpect(status().isConflict());
        assertThat(rows(productId)).extracting(row -> row.get("event_type")).containsExactly("product.created");

        admin.addVariant(productId, "{\"sku\":\"UP-1\"}").andExpect(status().isCreated());
        // A product with no option axes has room for one variant: a second
        // is refused by the aggregate (400), again after the transaction
        // began, and again nothing is appended.
        admin.addVariant(productId, "{\"sku\":\"UP-2\"}").andExpect(status().isBadRequest());
        assertThat(rows(productId)).extracting(row -> row.get("event_type"))
                .containsExactly("product.created", "product.updated");

        String unknown = UUID.randomUUID().toString();
        admin.update(unknown, "{\"title\":\"Nobody\"}").andExpect(status().isNotFound());
        admin.publish(unknown).andExpect(status().isNotFound());
        admin.remove(unknown).andExpect(status().isNotFound());
        assertThat(rows(unknown)).isEmpty();
    }

    @Test
    @DisplayName("a deleted product takes no more writes: each answers 404, appends nothing and leaves the row as the delete left it")
    void aDeletedProductTakesNoMoreWrites() throws Exception {
        // Why it matters: product.deleted is the last word on a product. Search
        // and inventory keep only the newest snapshot of each product, so a
        // product.updated numbered after it would put the deleted product
        // back on the storefront -- and publish would even set it ACTIVE again.
        String sku = freshSku();
        String ring = admin.create("{\"title\":\"Withdrawn Ring\",\"tags\":[]}");
        admin.addVariant(ring, "{\"sku\":\"" + sku + "\"}").andExpect(status().isCreated());
        String variantId = admin.variantId(ring, 0);
        com.oriah.catalog.PricedCatalogue.priceDirectly(jdbc, UUID.fromString(variantId), "4500.00");
        admin.publish(ring).andExpect(status().isOk());
        admin.remove(ring).andExpect(status().isNoContent());

        admin.price(variantId, "{\"unitPrice\":1.00,\"expectedPriceId\":null}").andExpect(status().isNotFound());
        admin.publish(ring).andExpect(status().isNotFound());
        admin.update(ring, "{\"title\":\"Back Again\"}").andExpect(status().isNotFound());
        admin.remove(ring).andExpect(status().isNotFound());

        assertThat(rows(ring)).extracting(row -> row.get("event_type")).containsExactly(
                "product.created", "product.updated", "product.updated", "product.deleted");
        assertThat(jdbc.queryForMap("SELECT is_deleted, status, title FROM products WHERE id = ?::uuid", ring))
                .containsEntry("is_deleted", true)
                .containsEntry("status", "ARCHIVED")
                .containsEntry("title", "Withdrawn Ring");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM product_variants WHERE id = ?::uuid",
                BigDecimal.class, variantId)).isEqualByComparingTo("4500.00");

        // A product with no option axes holds one variant, so adding a variant
        // is tried on one deleted before it had any: otherwise the aggregate
        // would refuse it (400) for a different reason.
        String bare = admin.create("{\"title\":\"Withdrawn Bangle\",\"tags\":[]}");
        admin.remove(bare).andExpect(status().isNoContent());

        admin.addVariant(bare, "{\"sku\":\"" + freshSku() + "\"}").andExpect(status().isNotFound());

        assertThat(rows(bare)).extracting(row -> row.get("event_type"))
                .containsExactly("product.created", "product.deleted");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_variants WHERE product_id = ?::uuid",
                Long.class, bare)).isZero();
    }

    @Test
    @DisplayName("the row is written in the write's own transaction: unseen outside it before commit, gone with a rollback")
    void theRowLivesAndDiesWithItsTransaction() throws Exception {
        String[] productId = new String[1];

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            try {
                // MockMvc runs the request on this thread, so the service's
                // transaction joins this one and commits only when it does.
                productId[0] = admin.create("{\"title\":\"Rolled Back Ring\",\"tags\":[]}");
                assertThat(rows(productId[0]))
                        .as("inside the transaction, the write and its outbox row are both there")
                        .hasSize(1);
                assertThat(countOnAnotherConnection(productId[0]))
                        .as("no other connection sees the row before the commit")
                        .isZero();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            tx.setRollbackOnly();
        });

        assertThat(rows(productId[0])).as("rolled back with the product it describes").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?::uuid",
                Long.class, productId[0])).isZero();
    }

    // ------------------------------------------------------------- helpers

    /**
     * The SKU in the golden files. SKUs are unique across the catalogue, and
     * the golden test's own product -- deleted, but its variant row kept, as
     * every deleted product's is -- already holds it in this shared database,
     * so other tests use a fresh SKU and put it in place of this one.
     */
    static final String GOLDEN_SKU = "\"sku\":\"AUR-1\"";

    static String freshSku() {
        return "AUR-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String goldenSku(String sku) {
        return "\"sku\":\"" + sku + "\"";
    }

    private List<Map<String, Object>> rows(String productId) {
        return jdbc.queryForList("""
                SELECT seq, event_type, topic, schema_version, actor, occurred_at, payload::text AS payload
                  FROM outbox
                 WHERE aggregate_type = 'product' AND aggregate_id = ?
                 ORDER BY seq""", productId);
    }

    /** A fresh pooled connection: outside any transaction this thread holds. */
    private long countOnAnotherConnection(String productId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM outbox WHERE aggregate_type = 'product' AND aggregate_id = ?")) {
            query.setString(1, productId);
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
