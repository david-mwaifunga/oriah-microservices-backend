package com.oriah.inventory.events;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What inventory learns of each product from catalog's snapshots, and the boxes it lets go of because of them. A product's boxes are
 * let go when the product is deleted, when it has been taken off sale (catalog's unpublish count went up), or when its default box
 * changed: judged by the STATE of the newest snapshot applied, not by the step between two of them, so a snapshot that arrives late,
 * twice or not at all (the next one carries the truth) cannot lose a release. A product only edited (a rename) keeps its boxes, and a
 * draft keeps the boxes it was given before it was published.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProductStateConsumerTest {

    private static final AtomicLong SEQ = new AtomicLong(1000);

    @Autowired
    private InputDestination inputDestination;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private StockService stock;

    @Autowired
    private InventoryRepository inventories;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID box;
    private UUID otherBox;

    @BeforeEach
    void stockOfTwoBoxes() {
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        box = UUID.randomUUID();
        otherBox = UUID.randomUUID();
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(7101));
        stock.applyReceipt(box(otherBox, "2026-11-09T08:00:01.123456Z"), context(7102));
    }

    private static VariantPayload variant(UUID id) {
        return new VariantPayload(id, "SKU-" + id, "Ring", new java.math.BigDecimal("1500.00"), "ZMW", true);
    }

    private static ProductUpdatedEvent snapshot(UUID productId, UUID variantId, String status, UUID defaultBox, Integer unpublishCount, String title) {
        return new ProductUpdatedEvent(productId, title, null, List.of("rings"), "JEWELLERY", status, List.of(variant(variantId)), "SELLABLE", defaultBox, unpublishCount);
    }

    private void send(Object payload, String type, UUID productId, long seq) {
        Message<Object> message = Relayed.envelope(MessageBuilder.withPayload(payload), UUID.randomUUID(), type, productId, seq).build();
        inputDestination.send(message, "catalog.product");
    }

    private void sendUpdated(ProductUpdatedEvent event, long seq) {
        send(event, ProductUpdatedEvent.TYPE, event.id(), seq);
    }

    private Inventory row(UUID variant) {
        return inventories.findById(variant).orElseThrow();
    }

    private String state(UUID product, String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM product_state WHERE product_id = ?", String.class, product);
    }

    private boolean hasCommitment(UUID product) {
        return jdbc.queryForObject("SELECT count(*) FROM box_commitment WHERE product_id = ?", Long.class, product) > 0;
    }

    private UUID aProductWithBoxes(int quantity) {
        UUID product = UUID.randomUUID();
        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 0, "Aurelia"), SEQ.incrementAndGet());
        commitments.set(product, quantity, "user:kc-maya");
        return product;
    }

    // ---------------------------------------------------------------- what is learned

    @Test
    @DisplayName("each snapshot's kind, status, default box and unpublish count are kept; a snapshot from before they existed reads as an ordinary product")
    void theStateIsKept() {
        UUID product = UUID.randomUUID();

        sendUpdated(snapshot(product, UUID.randomUUID(), "ACTIVE", box, 2, "Aurelia"), SEQ.incrementAndGet());

        assertThat(state(product, "kind")).isEqualTo("SELLABLE");
        assertThat(state(product, "status")).isEqualTo("ACTIVE");
        assertThat(state(product, "default_packaging_variant_id")).isEqualTo(box.toString());
        assertThat(state(product, "unpublish_count")).isEqualTo("2");
        assertThat(state(product, "deleted")).isEqualTo("false");

        UUID old = UUID.randomUUID();
        send(new ProductCreatedEvent(old, "Legacy", List.of("rings"), "JEWELLERY", "ACTIVE", List.of(variant(UUID.randomUUID())), null, null, null),
                ProductCreatedEvent.TYPE, old, SEQ.incrementAndGet());
        assertThat(state(old, "kind")).as("an absent kind reads as SELLABLE").isEqualTo("SELLABLE");
        assertThat(state(old, "unpublish_count")).isEqualTo("0");
        assertThat(state(old, "default_packaging_variant_id")).isNull();
    }

    // ---------------------------------------------------------------- what is kept

    @Test
    @DisplayName("a rename keeps the boxes: only a change that makes them pointless lets them go")
    void aRenameKeepsTheBoxes() {
        UUID product = aProductWithBoxes(40);

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 0, "Aurelia Renamed"), SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isTrue();
        assertThat(row(box).committed()).isEqualTo(40);
    }

    @Test
    @DisplayName("a draft keeps the boxes it was given while it was still a draft: priced and boxed first, published after")
    void aDraftKeepsItsBoxes() {
        UUID product = aProductWithBoxes(40);

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 0, "Aurelia"), SEQ.incrementAndGet());
        sendUpdated(snapshot(product, UUID.randomUUID(), "ACTIVE", box, 0, "Aurelia"), SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isTrue();
    }

    // ---------------------------------------------------------------- what is let go

    @Test
    @DisplayName("taking the product off sale lets its boxes go back to free, once, and writes the release into the history with the event that caused it")
    void unpublishReleases() {
        UUID product = aProductWithBoxes(40);

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 1, "Aurelia"), SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isFalse();
        assertThat(row(box).committed()).isZero();
        assertThat(row(box).free()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT kind || ':' || qty_delta FROM box_commitment_change WHERE product_id = ? ORDER BY id", String.class, product))
                .containsExactly("ALLOCATE:40", "RELEASE:-40");
        assertThat(jdbc.queryForObject("SELECT event_id FROM box_commitment_change WHERE product_id = ? AND kind = 'RELEASE'", UUID.class, product)).isNotNull();
    }

    @Test
    @DisplayName("changing the default box lets the boxes go: they were set aside for the old one")
    void aChangedBoxReleases() {
        UUID product = aProductWithBoxes(40);

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", otherBox, 0, "Aurelia"), SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isFalse();
        assertThat(row(box).committed()).isZero();
        assertThat(row(otherBox).committed()).as("nothing is set aside on the new box until someone says so").isZero();
    }

    @Test
    @DisplayName("clearing the default box lets the boxes go too")
    void aClearedBoxReleases() {
        UUID product = aProductWithBoxes(40);

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", null, 0, "Aurelia"), SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isFalse();
    }

    @Test
    @DisplayName("deleting the product lets them go")
    void deleteReleases() {
        UUID product = aProductWithBoxes(40);

        send(new ProductDeletedEvent(product), ProductDeletedEvent.TYPE, product, SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isFalse();
        assertThat(state(product, "deleted")).isEqualTo("true");
        assertThat(row(box).committed()).isZero();
    }

    @Test
    @DisplayName("only that product's boxes go: another product's set-aside on the same box is untouched")
    void otherProductsAreUntouched() {
        UUID gold = aProductWithBoxes(40);
        UUID silver = aProductWithBoxes(60);

        sendUpdated(snapshot(gold, UUID.randomUUID(), "DRAFT", box, 1, "Aurelia"), SEQ.incrementAndGet());

        assertThat(hasCommitment(gold)).isFalse();
        assertThat(hasCommitment(silver)).isTrue();
        assertThat(row(box).committed()).isEqualTo(60);
    }

    // ---------------------------------------------------------------- whatever order they arrive in

    @Test
    @DisplayName("the newer snapshot arriving BEFORE the older one still lets the boxes go: the older is discarded as stale, and the end state is the newer one's")
    void newestFirst() {
        UUID product = aProductWithBoxes(40);
        long published = SEQ.incrementAndGet();
        long unpublished = SEQ.incrementAndGet();

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 1, "Aurelia"), unpublished);   // the later fact arrives first
        sendUpdated(snapshot(product, UUID.randomUUID(), "ACTIVE", box, 0, "Aurelia"), published);    // the earlier one, late

        assertThat(hasCommitment(product)).isFalse();
        assertThat(state(product, "status")).isEqualTo("DRAFT");
        assertThat(state(product, "unpublish_count")).isEqualTo("1");
    }

    @Test
    @DisplayName("the same event twice is applied once: a second delivery changes nothing and writes no second release")
    void aRepeatedEventIsAppliedOnce() {
        UUID product = aProductWithBoxes(40);
        UUID eventId = UUID.randomUUID();
        Message<?> message = Relayed.envelope(MessageBuilder.withPayload(snapshot(product, UUID.randomUUID(), "DRAFT", box, 1, "Aurelia")),
                eventId, ProductUpdatedEvent.TYPE, product, SEQ.incrementAndGet()).build();

        inputDestination.send(message, "catalog.product");
        inputDestination.send(message, "catalog.product");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_commitment_change WHERE product_id = ? AND kind = 'RELEASE'", Long.class, product)).isEqualTo(1);
    }

    @Test
    @DisplayName("boxes set aside on a stale view of the product (inventory had not yet heard of the unpublish) are let go when the newer snapshot arrives")
    void aStaleViewIsCorrectedByTheNextSnapshot() {
        UUID product = UUID.randomUUID();
        sendUpdated(snapshot(product, UUID.randomUUID(), "ACTIVE", box, 0, "Aurelia"), SEQ.incrementAndGet());
        commitments.set(product, 40, "user:kc-maya");   // made while catalog had already unpublished it (count 1), news not yet here

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 1, "Aurelia"), SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isFalse();
        assertThat(row(box).committed()).isZero();
    }

    @Test
    @DisplayName("a commitment made after the unpublish is known is kept, because it was made under the new count")
    void aLaterCommitmentSurvivesAnEarlierUnpublish() {
        UUID product = UUID.randomUUID();
        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 1, "Aurelia"), SEQ.incrementAndGet());
        commitments.set(product, 40, "user:kc-maya");

        sendUpdated(snapshot(product, UUID.randomUUID(), "DRAFT", box, 1, "Aurelia renamed"), SEQ.incrementAndGet());

        assertThat(hasCommitment(product)).isTrue();
    }
}
