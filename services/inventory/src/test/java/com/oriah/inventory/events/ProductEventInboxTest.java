package com.oriah.inventory.events;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinder;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.EventHeaders;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.ProductRef;
import com.oriah.inventory.repository.ProductRefRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory's product_index is a copy of catalog's variants, kept from
 * catalog's product snapshots: each event carries the whole product as it
 * then was, numbered per product by seq (1, 2, 3 ...).
 *
 * <p>It applies them through {@code Inbox.onceInOrder}: a redelivered event
 * is applied once, and a snapshot older than the one already applied is
 * skipped, so an old title never overwrites a newer one. What cannot be
 * applied is refused loudly instead of skipped: the exception leaves the
 * consumer and no marker is left behind. On Kafka the oriah-events module then
 * publishes it to the dead-letter topic -- on its first delivery for a
 * refusal, which would fail the same way again, and after three deliveries
 * for a failure that may pass, such as the database being away.
 *
 * <p>The "through the binder" tests run on the test binder, which has no
 * dead-letter topic and none of the module's handling: it retries with the
 * binder's own retry, reading the same retryable-exceptions, and keeps the
 * last error where Kafka would dead-letter. So they show which failures are
 * tried again; how many deliveries Kafka makes, and what lands on the
 * dead-letter topic, ProductEventDeadLetterKafkaTest shows on a real broker.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class ProductEventInboxTest {

    @Autowired
    private InputDestination input;
    @Autowired
    private TestChannelBinder binder;
    @Autowired
    @Qualifier("productEvents")
    private Consumer<Message<byte[]>> productEvents;
    @Autowired
    private ProductRefRepository productRefs;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;
    /** The binding's own channel: the binder hands every try of every message to the consumer through it. */
    @Autowired
    @Qualifier("productEvents-in-0")
    private AbstractMessageChannel binding;
    private final List<ChannelInterceptor> interceptors = new ArrayList<>();
    private final List<String> tries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        binder.resetLastError();
        countTries();
    }

    @AfterEach
    void stopIntercepting() {
        interceptors.forEach(binding::removeInterceptor);
        interceptors.clear();
        tries.clear();
    }

    @Test
    @DisplayName("a duplicate is applied once: product.created coming again does not undo the update after it")
    void duplicateAppliesOnce() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();
        Message<byte[]> created = Relayed.productEvent(createdId, "product.created", productId, 1,
                snapshot(productId, variantId, "Aurelia - Ring Size M")).build();

        input.send(created, "catalog.product");
        input.send(Relayed.productEvent("product.updated", productId, 2,
                snapshot(productId, variantId, "Aurelia Solitaire - Ring Size M")).build(), "catalog.product");
        // Kafka hands product.created over again, after a rebalance say.
        input.send(created, "catalog.product");

        assertThat(productRefs.findById(variantId)).get().extracting(ProductRef::title)
                .as("the duplicate was applied once, at seq 1; the stock screen keeps the newer name")
                .isEqualTo("Aurelia Solitaire - Ring Size M");
        assertThat(markers(createdId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an older snapshot arriving after a newer one is skipped")
    void olderSnapshotIsSkipped() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        input.send(Relayed.productEvent("product.created", productId, 1,
                snapshot(productId, variantId, "Lume - Silver")).build(), "catalog.product");
        input.send(Relayed.productEvent("product.updated", productId, 3,
                snapshot(productId, variantId, "Lume Moonstone - Silver")).build(), "catalog.product");
        input.send(Relayed.productEvent("product.updated", productId, 2,
                snapshot(productId, variantId, "Lume Necklace - Silver")).build(), "catalog.product");

        assertThat(productRefs.findById(variantId)).get().extracting(ProductRef::title)
                .isEqualTo("Lume Moonstone - Silver");
        assertThat(lastSeq(productId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("an event type inventory never declared is refused, and nothing is recorded")
    void undeclaredTypeIsRefused() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> reviewed = Relayed.productEvent(eventId, "product.reviewed", productId, 1,
                snapshot(productId, variantId, "Aurelia - M")).build();

        assertThatThrownBy(() -> productEvents.accept(reviewed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.reviewed");

        assertThat(productRefs.findById(variantId)).isEmpty();
        assertThat(markers(eventId)).isZero();
        assertThat(aggregates(productId)).isZero();
    }

    /**
     * The seq that orders snapshots is kept per aggregate TYPE and id, so the
     * aggregate-type header decides whose history a snapshot is ordered in.
     * Catalog writes "product" (lower case: ProductService.AGGREGATE). A
     * product snapshot under any other aggregate-type -- even "Product" -- would
     * be applied while its seq went into another history, and a later, older
     * snapshot of the real product would then look new and overwrite it.
     */
    @Test
    @DisplayName("a product event whose aggregate-type is not catalog's \"product\" is refused, and nothing is recorded")
    void otherAggregateTypeIsRefused() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> misfiled = Relayed.productEvent(eventId, "product.created", productId, 1,
                snapshot(productId, variantId, "Aurelia - M")).setHeader(EventHeaders.AGGREGATE_TYPE, "Product").build();

        assertThatThrownBy(() -> productEvents.accept(misfiled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("as about aggregate 'product'")
                .hasMessageContaining("has aggregate-type 'Product'");

        assertThat(productRefs.findById(variantId)).isEmpty();
        assertThat(markers(eventId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_aggregate WHERE consumer = 'inventory' AND aggregate_id = ?",
                Long.class, productId.toString()))
                .as("no seq recorded under any aggregate type")
                .isZero();
    }

    @Test
    @DisplayName("an event-id that is not a UUID is refused, and nothing is recorded")
    void garbledEventIdIsRefused() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        Message<byte[]> garbled = Relayed.productEvent("product.created", productId, 1,
                        snapshot(productId, variantId, "Aurelia - M"))
                .setHeader(EventHeaders.EVENT_ID, "event-42").build();

        assertThatThrownBy(() -> productEvents.accept(garbled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EventHeaders.EVENT_ID);

        assertThat(productRefs.findById(variantId)).isEmpty();
        assertThat(aggregates(productId)).isZero();
    }

    @Test
    @DisplayName("a message with no schema-version is refused, and nothing is recorded")
    void missingSchemaVersionIsRefused() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message<byte[]> unversioned = Relayed.productEvent(eventId, "product.created", productId, 1,
                        snapshot(productId, variantId, "Aurelia - M"))
                .removeHeader(EventHeaders.SCHEMA_VERSION).build();

        assertThatThrownBy(() -> productEvents.accept(unversioned))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EventHeaders.SCHEMA_VERSION);

        assertThat(productRefs.findById(variantId)).isEmpty();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("when registering a variant throws, the exception leaves the consumer and no marker or seq is left")
    void throwingEffectLeavesNoMarker() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // A product and a variant both without a title: ProductRef refuses a
        // blank title, so registering the variant throws -- after the marker
        // and the seq were written, and both must roll back with it.
        Message<byte[]> untitled = Relayed.productEvent(eventId, "product.created", productId, 1, """
                {"id":"%s","title":" ","tags":[],"productType":"JEWELLERY","status":"ACTIVE",
                 "variants":[{"variantId":"%s","sku":"AUR-M","title":"","unitPrice":4500.00,
                              "currency":"ZMW","active":true}]}""".formatted(productId, variantId)).build();

        assertThatThrownBy(() -> productEvents.accept(untitled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");

        assertThat(productRefs.findById(variantId)).isEmpty();
        assertThat(markers(eventId)).isZero();
        assertThat(aggregates(productId))
                .as("seq 1 was not applied, so a retry must still find the product unseen")
                .isZero();
    }

    @Test
    @DisplayName("a snapshot of one product in an envelope naming another is refused, and nothing is recorded")
    void snapshotMustBeOfTheEnvelopesProduct() {
        UUID envelopeProduct = UUID.randomUUID();
        UUID otherProduct = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // The seq that orders snapshots is counted per aggregate-id. Applying
        // this body to otherProduct would order it by envelopeProduct's seq.
        Message<byte[]> mismatched = Relayed.productEvent(eventId, "product.created", envelopeProduct, 1,
                snapshot(otherProduct, variantId, "Aurelia - M")).build();

        assertThatThrownBy(() -> productEvents.accept(mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(otherProduct.toString());

        assertThat(productRefs.findById(variantId)).isEmpty();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("through the binder: a failure reaches the binder's error handling instead of vanishing in a log")
    void failureReachesTheBinder() {
        UUID productId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // The binder hands the message to its error handling -- on Kafka, the
        // module's, which dead-letters it. The test binder cannot run a DLQ;
        // it keeps the last error instead.
        input.send(Relayed.productEvent(eventId, "product.reviewed", productId, 1,
                snapshot(productId, UUID.randomUUID(), "Aurelia - M")).build(), "catalog.product");

        assertThat(binder.getLastError())
                .as("the consumer threw out to the binder, so Kafka would dead-letter it")
                .isNotNull();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("through the binder: a refused event goes to the dead-letter topic on its first try")
    void refusalIsNotRetried() {
        UUID productId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        input.send(Relayed.productEvent(eventId, "product.reviewed", productId, 1,
                snapshot(productId, UUID.randomUUID(), "Aurelia - M")).build(), "catalog.product");

        assertThat(tries(eventId))
                .as("an undeclared type is refused the same way every time: a retry cannot change the answer, "
                        + "it only holds up the products behind it")
                .isEqualTo(1);
        assertThat(binder.getLastError()).as("it is still dead-lettered").isNotNull();
        assertThat(markers(eventId)).isZero();
    }

    @Test
    @DisplayName("through the binder: a body that cannot be read goes to the dead-letter topic on its first try")
    void unreadableBodyIsNotRetried() {
        UUID productId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // A variant id that is not a UUID: the body cannot be read as a ProductCreatedEvent.
        String unreadable = """
                {"id":"%s","title":"Aurelia","tags":["rings"],"productType":"JEWELLERY","status":"ACTIVE",
                 "variants":[{"variantId":"not-a-uuid","sku":"AUR-M","title":"Aurelia - M","unitPrice":4500.00,
                              "currency":"ZMW","active":true}]}""".formatted(productId);

        input.send(Relayed.productEvent(eventId, "product.created", productId, 1, unreadable).build(),
                "catalog.product");

        assertThat(tries(eventId))
                .as("the same bytes fail to read the same way on every try")
                .isEqualTo(1);
        assertThat(binder.getLastError()).as("it is still dead-lettered").isNotNull();
        assertThat(markers(eventId)).isZero();
        assertThat(aggregates(productId)).isZero();
    }

    @Test
    @DisplayName("through the binder: a failure that can pass on a later try is retried, and the snapshot applied")
    void passingFailureIsRetried() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // Stands in for the database being away for a moment: the first try
        // fails on the way into the consumer, the retry gets through.
        intercept(failsFirstTry(eventId));

        input.send(Relayed.productEvent(eventId, "product.created", productId, 1,
                snapshot(productId, variantId, "Lume Necklace - Silver")).build(), "catalog.product");

        assertThat(tries(eventId)).isEqualTo(2);
        assertThat(productRefs.findById(variantId)).get().extracting(ProductRef::title)
                .isEqualTo("Lume Necklace - Silver");
        assertThat(markers(eventId)).isEqualTo(1);
        assertThat(lastSeq(productId)).isEqualTo(1L);
        assertThat(binder.getLastError()).as("nothing was dead-lettered").isNull();
    }

    /** Every try the binder makes passes through the binding's channel; this counts them by event-id. */
    private void countTries() {
        intercept(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                tries.add(String.valueOf(message.getHeaders().get(EventHeaders.EVENT_ID)));
                return message;
            }
        });
    }

    private long tries(UUID eventId) {
        return tries.stream().filter(eventId.toString()::equals).count();
    }

    private static ChannelInterceptor failsFirstTry(UUID eventId) {
        AtomicBoolean failed = new AtomicBoolean();
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (eventId.toString().equals(message.getHeaders().get(EventHeaders.EVENT_ID))
                        && failed.compareAndSet(false, true)) {
                    throw new TransientDataAccessResourceException("the database is restarting");
                }
                return message;
            }
        };
    }

    private void intercept(ChannelInterceptor interceptor) {
        binding.addInterceptor(interceptor);
        interceptors.add(interceptor);
    }

    private long markers(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'inventory' AND event_id = ?",
                Long.class, eventId);
    }

    private long aggregates(UUID productId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM processed_aggregate
                WHERE consumer = 'inventory' AND aggregate_type = 'product' AND aggregate_id = ?""",
                Long.class, productId.toString());
    }

    private Long lastSeq(UUID productId) {
        return jdbc.queryForObject("""
                SELECT last_seq FROM processed_aggregate
                WHERE consumer = 'inventory' AND aggregate_type = 'product' AND aggregate_id = ?""",
                Long.class, productId.toString());
    }

    /** A product.created / product.updated body in catalog's shape, with one variant. */
    private static String snapshot(UUID productId, UUID variantId, String variantTitle) {
        return """
                {"id":"%s","title":"Aurelia","tags":["rings"],"productType":"JEWELLERY","status":"ACTIVE",
                 "variants":[{"variantId":"%s","sku":"AUR-M","title":"%s","unitPrice":4500.00,
                              "currency":"ZMW","active":true}]}""".formatted(productId, variantId, variantTitle);
    }
}
