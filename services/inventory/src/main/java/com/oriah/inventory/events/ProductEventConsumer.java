package com.oriah.inventory.events;

import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@link com.oriah.inventory.events package-info} bridge) drives the
 * switch — payload deserialization runs only after we know what shape to
 * expect.
 *
 * <p>Binding name: the bean {@code productEvents} becomes the function
 * binding {@code productEvents-in-0}; YAML wires that to the
 * {@code catalog.product} destination.
 *
 * <p>Idempotency comes from upsert semantics: created and updated both
 * call {@code save()} on the {@code ProductRef} entity keyed by
 * variantId, so a replayed event from at-least-once Kafka delivery just
 * overwrites the row with identical data.
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

    private static final String HEADER_EVENT_TYPE = "event-type";

    @Bean
    public Consumer<Message<byte[]>> productEvents(
            ProductRefRepository repository,
            ObjectMapper objectMapper) {
        return new ProductEventHandler(repository, objectMapper);
    }

    /**
     * Inner handler so we can put {@code @Transactional} on the
     * dispatch method — bean methods on a {@code @Configuration} class
     * don't go through the proxy that {@code @Transactional} relies on.
     */
    static class ProductEventHandler implements Consumer<Message<byte[]>> {
        private final ProductRefRepository repository;
        private final ObjectMapper objectMapper;

        ProductEventHandler(ProductRefRepository repository, ObjectMapper objectMapper) {
            this.repository = repository;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            Object typeHeader = message.getHeaders().get(HEADER_EVENT_TYPE);
            String type = typeHeader == null ? null : typeHeader.toString();
            try {
                switch (type) {
                    case null -> log.warn("Skipping message with no event-type header headers={}", message.getHeaders());
                    case ProductCreatedEvent.TYPE -> handleCreated(
                            objectMapper.readValue(message.getPayload(), ProductCreatedEvent.class));
                    case ProductUpdatedEvent.TYPE -> handleUpdated(
                            objectMapper.readValue(message.getPayload(), ProductUpdatedEvent.class));
                    case ProductDeletedEvent.TYPE -> handleDeleted(
                            objectMapper.readValue(message.getPayload(), ProductDeletedEvent.class));
                    default -> log.warn("Skipping unrecognized event-type={}", type);
                }
            } catch (Exception e) {
                // Logging-and-swallowing here makes the consumer NOT retry on
                // malformed payloads — they go to a dead-letter topic in a
                // production setup. Out of scope for this iteration; we'd
                // configure spring.cloud.stream.kafka.bindings.productEvents-in-0
                // .consumer.dlqName to wire one up.
                log.error("Failed to handle event-type={} payload-length={}", type,
                        message.getPayload().length, e);
            }
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
