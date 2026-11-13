package com.oriah.inventory.events;

import java.util.List;
import java.util.Optional;
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
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.ProductRef;
import com.oriah.inventory.repository.ProductRefRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code productEvents-in-0} consumer binding correctly
 * translates inbound Kafka messages into row mutations on the
 * {@code product_index} table.
 *
 * <p>Uses the Spring Cloud Stream test binder
 * ({@link TestChannelBinderConfiguration}) so the test can inject messages
 * via an {@link InputDestination} without a real Kafka. The consumer is the
 * production {@link ProductEventConsumer} {@code @Bean Consumer<...>} —
 * we exercise the binding wiring end-to-end up to the JPA write.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class ProductEventConsumerTest {

    /** Catalog numbers each product's events 1, 2, 3 ...; one rising counter keeps every test's sends in order. */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private InputDestination inputDestination;

    @Autowired
    private ProductRefRepository productRefs;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanIndex() {
        productRefs.deleteAll();
    }

    /** A variant payload with the option summary catalog composes. */
    private static VariantPayload variant(UUID variantId, String title) {
        return new VariantPayload(variantId, "SKU-" + title, title,
                new java.math.BigDecimal("4500.00"), "ZMW", true);
    }

    private static ProductCreatedEvent created(UUID productId, String title,
                                               VariantPayload... variants) {
        return new ProductCreatedEvent(productId, title, List.of("rings"),
                "JEWELLERY", "ACTIVE", List.of(variants), "SELLABLE", null, 0);
    }

    private static ProductUpdatedEvent updated(UUID productId, String title,
                                               VariantPayload... variants) {
        return new ProductUpdatedEvent(productId, title, null, List.of("rings"),
                "JEWELLERY", "ACTIVE", List.of(variants), "SELLABLE", null, 0);
    }

    @Test
    @DisplayName("one ProductRef row is written PER VARIANT, keyed by the variant id")
    void createdEventInsertsOneRowPerVariant() {
        UUID productId = UUID.randomUUID();
        UUID sizeM = UUID.randomUUID();
        UUID sizeL = UUID.randomUUID();

        send(created(productId, "Aurelia",
                variant(sizeM, "Aurelia - Ring Size M"),
                variant(sizeL, "Aurelia - Ring Size L")), ProductCreatedEvent.TYPE);

        // Keyed by VARIANT, not by product. This table's primary key was
        // previously fed the product id, which was only correct while a
        // product had exactly one variant whose id was the product's own.
        assertThat(productRefs.findById(sizeM)).isPresent();
        assertThat(productRefs.findById(sizeL)).isPresent();
        assertThat(productRefs.findById(productId)).isEmpty();
        assertThat(productRefs.count()).isEqualTo(2L);

        // The option summary is what makes two rows of one product
        // distinguishable on an operator's screen.
        assertThat(productRefs.findById(sizeM).orElseThrow().title())
                .isEqualTo("Aurelia - Ring Size M");
        // And each row records which product it belongs to, which is how
        // product.deleted finds them.
        assertThat(productRefs.findById(sizeM).orElseThrow().productId()).isEqualTo(productId);
    }

    @Test
    @DisplayName("a product with NO variants registers nothing, because nothing is purchasable yet")
    void createdWithoutVariantsRegistersNothing() {
        UUID productId = UUID.randomUUID();
        send(created(productId, "Freshly Created"), ProductCreatedEvent.TYPE);

        // This is the state of every newly created product: it exists before
        // it has variants. Writing a row keyed by the product id as a fallback
        // would reintroduce exactly the confusion this change removes -- an
        // entry that looks purchasable and is not.
        assertThat(productRefs.count()).isZero();
    }

    @Test
    @DisplayName("an OLD-SHAPE event with no variants field is harmless, not a crash")
    void oldShapeEventIsHarmless() {
        UUID productId = UUID.randomUUID();
        // The topic has no configured retention and there is no outbox, so
        // pre-upgrade messages can still be redelivered. `variants` is null
        // rather than empty in that case.
        send(new ProductCreatedEvent(productId, "Legacy", List.of("rings"), null, null, null, null, null, null),
                ProductCreatedEvent.TYPE);

        assertThat(productRefs.count()).isZero();
    }

    @Test
    @DisplayName("replaying the same event is idempotent")
    void replayedCreatedIsIdempotent() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        var event = message(created(productId, "Aurelia", variant(variantId, "Aurelia - M")),
                ProductCreatedEvent.TYPE);

        // The same event twice: same event-id, as Kafka redelivers it.
        inputDestination.send(event, "catalog.product");
        inputDestination.send(event, "catalog.product");

        assertThat(productRefs.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("product.updated refreshes each variant's title")
    void updatedEventChangesTitles() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        send(created(productId, "Aurelia", variant(variantId, "Aurelia - M")),
                ProductCreatedEvent.TYPE);

        send(updated(productId, "Aurelia Renamed", variant(variantId, "Aurelia Renamed - M")),
                ProductUpdatedEvent.TYPE);

        assertThat(productRefs.findById(variantId).orElseThrow().title())
                .isEqualTo("Aurelia Renamed - M");
    }

    @Test
    @DisplayName("product.updated for an unknown variant is upserted")
    void updatedForUnknownIdInserts() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        // No prior created event. Treating updated-on-unknown as an upsert is
        // the right call when events can arrive out of order -- no message key
        // is set, so there is no per-product ordering guarantee -- or when the
        // created event predates this consumer's subscription.
        send(updated(productId, "Aurelia", variant(variantId, "Aurelia - M")),
                ProductUpdatedEvent.TYPE);

        assertThat(productRefs.findById(variantId)).isPresent();
    }

    @Test
    @DisplayName("a variant ABSENT from a later update is kept, not deleted")
    void absentVariantsAreKept() {
        UUID productId = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        send(created(productId, "Aurelia",
                variant(kept, "Aurelia - M"), variant(removed, "Aurelia - L")),
                ProductCreatedEvent.TYPE);
        assertThat(productRefs.count()).isEqualTo(2L);

        // The event is a full snapshot, so this variant is gone in catalog.
        send(updated(productId, "Aurelia", variant(kept, "Aurelia - M")),
                ProductUpdatedEvent.TYPE);

        // Still here. Its title is the only readable label for stock that
        // physically exists, and the admin list LEFT JOINs this table exactly
        // so such a row still appears -- dropping it would leave a bare UUID
        // beside a quantity.
        assertThat(productRefs.findById(removed)).isPresent();
        assertThat(productRefs.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("product.deleted FLAGS every one of the product's variant rows rather than deleting them")
    void deletedEventFlagsRows() {
        UUID productId = UUID.randomUUID();
        UUID sizeM = UUID.randomUUID();
        UUID sizeL = UUID.randomUUID();
        send(created(productId, "Aurelia",
                variant(sizeM, "Aurelia - M"), variant(sizeL, "Aurelia - L")),
                ProductCreatedEvent.TYPE);

        send(new ProductDeletedEvent(productId), ProductDeletedEvent.TYPE);

        // Both rows survive, flagged. Deleting them would lose the only title
        // an operator has for stock that still physically exists; leaving them
        // unflagged would show a live-looking product that is gone.
        assertThat(productRefs.findById(sizeM)).isPresent();
        assertThat(productRefs.findById(sizeL)).isPresent();
        assertThat(productRefs.findById(sizeM).orElseThrow().isProductDeleted()).isTrue();
        assertThat(productRefs.findById(sizeL).orElseThrow().isProductDeleted()).isTrue();
    }

    @Test
    @DisplayName("a stale product.updated arriving AFTER a delete does not resurrect the product")
    void staleUpdateDoesNotClearTheFlag() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        send(created(productId, "Aurelia", variant(variantId, "Aurelia - M")),
                ProductCreatedEvent.TYPE);
        send(new ProductDeletedEvent(productId), ProductDeletedEvent.TYPE);

        // Reachable two ways: at-least-once delivery can redeliver an older
        // update, and no message key is set on the topic so with more than one
        // partition ordering is not guaranteed at all. A product that has been
        // deleted cannot subsequently be updated, so a later update for it is
        // always stale.
        send(updated(productId, "Aurelia Resurrected", variant(variantId, "Aurelia - M")),
                ProductUpdatedEvent.TYPE);

        var ref = productRefs.findById(variantId).orElseThrow();
        assertThat(ref.isProductDeleted())
                .as("the flag is monotonic, which is what makes this consumer correct "
                        + "without timestamps to compare")
                .isTrue();
    }

    @Test
    @DisplayName("product.deleted for an unknown product is a no-op, not an error")
    void deletedForUnknownIdIsNoOp() {
        send(new ProductDeletedEvent(UUID.randomUUID()), ProductDeletedEvent.TYPE);
        assertThat(productRefs.count()).isZero();
    }

    /**
     * Publishes through the test binder, exactly as catalog's relay would:
     * the payload plus the envelope headers -- the `event-type` the consumer
     * dispatches on, and the event-id and per-product seq the Inbox requires.
     */
    private void send(Object payload, String type) {
        inputDestination.send(message(payload, type), "catalog.product");
    }

    /**
     * A new event: its own event-id, and a seq above every one sent before,
     * so the events of each test are applied in the order they are sent.
     */
    private static Message<Object> message(Object payload, String type) {
        UUID productId = switch (payload) {
            case ProductCreatedEvent created -> created.id();
            case ProductUpdatedEvent updated -> updated.id();
            case ProductDeletedEvent deleted -> deleted.id();
            default -> throw new IllegalArgumentException("not a product event: " + payload);
        };
        return Relayed.envelope(MessageBuilder.withPayload(payload), UUID.randomUUID(), type, productId,
                SEQ.incrementAndGet()).build();
    }
}
