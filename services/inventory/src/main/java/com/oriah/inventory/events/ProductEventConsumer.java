package com.oriah.inventory.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;
import com.oriah.inventory.model.ProductRef;
import com.oriah.inventory.repository.ProductRefRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Subscribes to {@code catalog.product} via Spring Cloud Stream's
 * functional model.
 *
 * <p>One bean handles all three event types ({@code product.created},
 * {@code product.updated}, {@code product.deleted}) because they all land
 * on the same topic. The {@code event-type} header (set by catalog's
 * outbox relay) drives the switch — payload deserialization runs only
 * after we know what shape to expect.
 *
 * <p>Binding name: the bean {@code productEvents} becomes the function
 * binding {@code productEvents-in-0}; YAML wires that to the
 * {@code catalog.product} destination.
 *
 * <h2>Only the newest snapshot counts</h2>
 *
 * product_index is a PROJECTION: a copy of catalog's variants, and each
 * product event carries the whole product as it then was, numbered per
 * product by seq. So each event goes through {@link Inbox#onceInOrder}: one
 * already applied (Kafka redelivers) is skipped, and so is one older than the
 * newest already applied to that product -- an old title never overwrites a
 * newer one. The newest seq per product is kept in processed_aggregate, in
 * the same transaction as the change to product_index.
 *
 * <h2>Nothing is swallowed</h2>
 *
 * An event that cannot be applied -- a type or payload version not declared
 * below, a missing or garbled envelope header, a body that breaks the
 * contract -- throws. The transaction rolls back with its record, and the
 * binder publishes it to {@code catalog.product.dlq.inventory}: on the first
 * try for a refusal like these, which would fail the same way again, and after
 * its retries for anything else, such as the database being away
 * (application.yaml). Logging and carrying on, as this consumer once did,
 * lost such an event for good.
 *
 * <p>Why we read the body manually instead of typed
 * {@code Consumer<ProductCreatedEvent>}: Spring Cloud Stream would happily
 * deserialize directly, but it needs to know the target type per binding
 * — there's only one consumer here and three payload shapes. Reading
 * the raw bytes and branching is simpler than three parallel bindings.
 */
@Configuration
@Slf4j
public class ProductEventConsumer {

    /**
     * Every type catalog publishes on catalog.product, all handled: each is a
     * full snapshot product_index needs. Nothing is ignored. Payload version 1
     * is {@link ProductCreatedEvent}, {@link ProductUpdatedEvent} and
     * {@link ProductDeletedEvent}.
     */
    static final InboxConsumer INVENTORY = InboxConsumer.named("inventory")
            .handles(ProductCreatedEvent.TYPE, 1)
            .handles(ProductUpdatedEvent.TYPE, 1)
            .handles(ProductDeletedEvent.TYPE, 1);

    @Bean
    public Consumer<Message<byte[]>> productEvents(
            Inbox inbox,
            ProductRefRepository repository,
            ObjectMapper objectMapper) {
        return new ProductEventHandler(inbox, repository, objectMapper);
    }

    /**
     * Inner handler so we can put {@code @Transactional} on the
     * dispatch method — bean methods on a {@code @Configuration} class
     * don't go through the proxy that {@code @Transactional} relies on.
     */
    static class ProductEventHandler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final ProductRefRepository repository;
        private final ObjectMapper objectMapper;

        ProductEventHandler(Inbox inbox, ProductRefRepository repository, ObjectMapper objectMapper) {
            this.inbox = inbox;
            this.repository = repository;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.onceInOrder(INVENTORY, message.getHeaders(), () -> apply(message));
        }

        /** Runs only for a handled snapshot newer than any applied to its product; the Inbox checked the headers. */
        private void apply(Message<byte[]> message) {
            String type = header(message, EventHeaders.EVENT_TYPE);
            switch (type) {
                case ProductCreatedEvent.TYPE ->
                        handleCreated(snapshot(message, ProductCreatedEvent.class, ProductCreatedEvent::id));
                case ProductUpdatedEvent.TYPE ->
                        handleUpdated(snapshot(message, ProductUpdatedEvent.class, ProductUpdatedEvent::id));
                case ProductDeletedEvent.TYPE ->
                        handleDeleted(snapshot(message, ProductDeletedEvent.class, ProductDeletedEvent::id));
                // Unreachable while INVENTORY and this switch list the same types.
                default -> throw new IllegalStateException("no handling for declared event type " + type);
            }
        }

        /**
         * Reads the body, which must be a snapshot of the product the envelope
         * names. The seq that orders snapshots is counted per aggregate-id, so
         * a body about another product would be ordered by the wrong product's
         * seq; it is refused instead.
         */
        private <T> T snapshot(Message<byte[]> message, Class<T> type, Function<T, UUID> productIdOf) {
            T event;
            try {
                event = objectMapper.readValue(message.getPayload(), type);
            } catch (IOException e) {
                throw new UncheckedIOException("product event body does not match " + type.getSimpleName(), e);
            }
            UUID productId = productIdOf.apply(event);
            String aggregateId = header(message, EventHeaders.AGGREGATE_ID);
            if (productId == null || !productId.toString().equals(aggregateId)) {
                throw new IllegalArgumentException("snapshot of product " + productId
                        + " arrived in an envelope for product " + aggregateId);
            }
            return event;
        }

        /** A header as text: a String as the binder hands it over, or raw UTF-8 bytes as Kafka carries it. */
        private static String header(Message<?> message, String name) {
            Object value = message.getHeaders().get(name);
            return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
        }

        private void handleCreated(ProductCreatedEvent event) {
            log.info("Consuming product.created id={} title=\"{}\" variants={}",
                    event.id(), event.title(),
                    event.variants() == null ? 0 : event.variants().size());
            upsertVariants(event.id(), event.title(), event.variants());
        }

        private void handleUpdated(ProductUpdatedEvent event) {
            log.info("Consuming product.updated id={} title=\"{}\" variants={}",
                    event.id(), event.title(),
                    event.variants() == null ? 0 : event.variants().size());
            upsertVariants(event.id(), event.title(), event.variants());
        }

        /**
         * Registers one reference per VARIANT.
         *
         * <h2>Why per variant and not per product</h2>
         *
         * This table's primary key has always been fed the catalog PRODUCT id,
         * which was correct only while a product had exactly one variant whose
         * id was the product's own. Catalog now sells variants, so for
         * anything created since its variants migration that id buys nothing
         * and the actual variants would have had no stock reference at all.
         *
         * <h2>Three reconciliation rules, each with a reason</h2>
         *
         * <p>ABSENT VARIANTS ARE NOT DELETED. The event carries a full
         * snapshot, so a variant missing from it has been removed in catalog
         * — but its title is still the only readable label for stock that
         * physically exists, and the admin list LEFT JOINs this table exactly
         * so such a row still appears. Dropping it would leave a bare UUID.
         *
         * <p>A PRODUCT WITH NO VARIANTS REGISTERS NOTHING. That is the state
         * of a freshly created product, and there is nothing purchasable to
         * track stock against yet. The variants arrive on the first update
         * after one is added.
         *
         * <p>THE PRODUCT TITLE IS NOT WRITTEN AS A FALLBACK when there are no
         * variants. A row keyed by the product id would reintroduce precisely
         * the confusion this change removes: an entry that looks like a
         * purchasable thing and is not.
         */
        private void upsertVariants(java.util.UUID productId, String productTitle,
                                    java.util.List<VariantPayload> variants) {
            if (variants == null || variants.isEmpty()) {
                log.debug("product {} has no variants; nothing to register", productId);
                return;
            }
            for (var variant : variants) {
                if (variant.variantId() == null) {
                    log.warn("skipping a variant with no id on product {}", productId);
                    continue;
                }
                // The event's per-variant title already includes the option
                // summary ("Aurelia Solitaire Ring - Ring Size M"), composed
                // by catalog. Falling back to the product title keeps a row
                // readable if a producer ever omits it.
                String title = variant.title() == null || variant.title().isBlank()
                        ? productTitle
                        : variant.title();

                ProductRef existing = repository.findById(variant.variantId()).orElse(null);
                if (existing == null) {
                    repository.save(new ProductRef(variant.variantId(), productId, title));
                } else {
                    // Managed entity; JPA dirty checking persists on commit.
                    // updateTitle deliberately does not clear productDeleted
                    // -- see its javadoc on why the flag is monotonic.
                    existing.updateTitle(title);
                }
            }
        }

        /**
         * Flags every one of the product's references rather than deleting
         * them.
         *
         * <h2>Why nothing is deleted here</h2>
         *
         * The row's title is the only readable label for stock that still
         * physically exists. The admin inventory list LEFT JOINs this table so
         * that a stock row with no catalog reference still appears; delete the
         * reference and that screen shows a bare UUID and a quantity, which
         * nobody can act on. Leave it unflagged and the screen shows a
         * live-looking product that is gone. Flagged, it can read
         * "Aurelia 18k (deleted product)" beside the real figure.
         *
         * <h2>What is deliberately NOT touched</h2>
         *
         * The {@code inventory} rows themselves. Stock that physically exists
         * does not stop existing because a catalogue entry was withdrawn, and
         * reconciling that is an operator's decision rather than a consumer's.
         *
         * <p>Keyed on {@code product_id}, which is why that column exists: the
         * event names a product and the rows are keyed by variant, so before
         * it there was no way to find them.
         */
        private void handleDeleted(ProductDeletedEvent event) {
            log.info("Consuming product.deleted id={}", event.id());
            var refs = repository.findByProductId(event.id());
            if (refs.isEmpty()) {
                // A delete for an id this consumer never saw is a no-op, which
                // is the same semantic the previous deleteById had.
                log.debug("no product_index rows for deleted product {}", event.id());
                return;
            }
            refs.forEach(ProductRef::markProductDeleted);
            log.info("Flagged {} variant reference(s) of deleted product {}",
                    refs.size(), event.id());
        }
    }
}
