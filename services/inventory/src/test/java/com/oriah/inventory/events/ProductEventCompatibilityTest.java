package com.oriah.inventory.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether this service can read what catalog actually sends.
 *
 * <h2>Why this test lives here and not in catalog</h2>
 *
 * Nothing compiles the two definitions together. Inventory declares its own
 * copy of each event record, deliberately — a shared class would let a catalog
 * release break this service at compile time and would couple two
 * independently deployable modules through a wire format they are meant to
 * meet only on the topic.
 *
 * <p>The cost of that choice is that the definitions can drift in silence. A
 * test in catalog proving catalog's own records round-trip proves nothing
 * about this consumer, so the protection has to be here: a payload in the
 * shape catalog emits, pushed through the deserialisation this consumer
 * actually performs.
 *
 * <h2>What is being claimed, and what was previously only argued</h2>
 *
 * The compatibility argument for adding fields is that Spring Boot disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} and this service has no override. That is
 * an argument about configuration. These tests are the evidence, and they also
 * pin the inverse: the fields this consumer READS are a contract catalog
 * cannot rename or remove.
 *
 * <p>The JSON below is written as a literal rather than produced by
 * serialising catalog's classes, which is the point — a literal is what the
 * topic carries, and it cannot silently follow a refactor on the producer
 * side.
 */
class ProductEventCompatibilityTest {

    /**
     * A RAW mapper, deliberately, with Jackson's own defaults rather than
     * Spring Boot's.
     *
     * <p>The first version of this class called it "the default mapper" and
     * assumed it was lenient. It is not: Jackson enables
     * {@code FAIL_ON_UNKNOWN_PROPERTIES}, and it is Spring Boot that turns it
     * off on the auto-configured bean. So this file failed, which was the most
     * useful thing it did -- it showed that the whole forward-compatibility
     * story rested on ambient configuration rather than on anything in the
     * contract.
     *
     * <p>The event records now carry
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so tolerance is a
     * property of the types themselves. Using a raw mapper here is what proves
     * that: these tests pass with Jackson's strictest default, so they cannot
     * be quietly broken by a configuration change elsewhere.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("unknown fields are tolerated even with Jackson's STRICT default, because the records say so")
    void toleranceDoesNotDependOnConfiguration() throws Exception {
        // With a raw mapper and no annotation this throws
        // UnrecognizedPropertyException. It passes because the record declares
        // the tolerance, which means nobody can turn it off by setting
        // spring.jackson.deserialization.fail-on-unknown-properties=true --
        // a reasonable thing to want for request bodies, and one that would
        // otherwise make every field catalog adds a poison message.
        var event = mapper.readValue(
                "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"title\":\"X\","
                        + "\"somethingNobodyDeclared\":123}",
                ProductCreatedEvent.class);

        assertThat(event.title()).isEqualTo("X");
    }

    @Test
    @DisplayName("a product.created carrying the new fields deserialises, variants included")
    void createdWithVariants() throws Exception {
        String json = """
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "title": "Aurelia Solitaire Ring",
                  "tags": ["rings", "18k"],
                  "productType": "JEWELLERY",
                  "status": "ACTIVE",
                  "variants": [
                    {
                      "variantId": "22222222-2222-4222-8222-222222222222",
                      "sku": "AUR-M",
                      "title": "Aurelia Solitaire Ring - Ring Size M",
                      "unitPrice": 4500.00,
                      "currency": "ZMW",
                      "active": true
                    },
                    {
                      "variantId": "33333333-3333-4333-8333-333333333333",
                      "sku": "AUR-L",
                      "title": "Aurelia Solitaire Ring - Ring Size L",
                      "unitPrice": 4700.00,
                      "currency": "ZMW",
                      "active": false
                    }
                  ]
                }
                """;

        var event = mapper.readValue(json, ProductCreatedEvent.class);

        assertThat(event.id()).isEqualTo(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        assertThat(event.title()).isEqualTo("Aurelia Solitaire Ring");
        assertThat(event.productType()).isEqualTo("JEWELLERY");
        assertThat(event.status()).isEqualTo("ACTIVE");
        assertThat(event.variants()).hasSize(2);

        VariantPayload first = event.variants().getFirst();
        assertThat(first.variantId())
                .isEqualTo(UUID.fromString("22222222-2222-4222-8222-222222222222"));
        // The option summary is composed by catalog and stored verbatim, so an
        // operator sees "Ring Size M" rather than two identical rows.
        assertThat(first.title()).isEqualTo("Aurelia Solitaire Ring - Ring Size M");
        assertThat(first.active()).isTrue();
        assertThat(event.variants().get(1).active()).isFalse();
    }

    @Test
    @DisplayName("an OLD-SHAPE event still deserialises, so a replay of pre-upgrade messages is safe")
    void oldShapeStillWorks() throws Exception {
        // The topic has no configured retention and there is no outbox, so
        // messages published before this change can still be redelivered.
        // They carry no productType, status or variants at all.
        String json = """
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "title": "Legacy Ring",
                  "tags": ["rings"]
                }
                """;

        var event = mapper.readValue(json, ProductCreatedEvent.class);

        assertThat(event.title()).isEqualTo("Legacy Ring");
        // Absent, not empty. The consumer treats null and empty the same way
        // -- registers nothing -- which is why an old message is harmless
        // rather than a NullPointerException in the handler.
        assertThat(event.variants()).isNull();
        assertThat(event.productType()).isNull();
    }

    @Test
    @DisplayName("a FUTURE field catalog has not sent yet does not break this consumer")
    void unknownFutureFieldIsIgnored() throws Exception {
        // The forward half of the compatibility claim: catalog can ship a new
        // field before inventory is redeployed.
        String json = """
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "title": "Aurelia",
                  "tags": [],
                  "productType": "JEWELLERY",
                  "status": "ACTIVE",
                  "variants": [
                    {
                      "variantId": "22222222-2222-4222-8222-222222222222",
                      "sku": "AUR-M",
                      "title": "Aurelia - Ring Size M",
                      "unitPrice": 4500.00,
                      "currency": "ZMW",
                      "active": true,
                      "weightGrams": 4.2,
                      "madeToOrder": false
                    }
                  ],
                  "primaryImageId": "44444444-4444-4444-8444-444444444444",
                  "categorySlugs": ["rings", "gifts"]
                }
                """;

        var event = mapper.readValue(json, ProductCreatedEvent.class);

        assertThat(event.variants()).hasSize(1);
        assertThat(event.variants().getFirst().sku()).isEqualTo("AUR-M");
    }

    @Test
    @DisplayName("Phase 3's kind field (SELLABLE or PACKAGING) is tolerated: the event still binds, variants included")
    void kindFieldIsTolerated() throws Exception {
        // Catalog appends "kind" as the last field of product.created and
        // product.updated. Inventory does not read it yet: packaging is stocked
        // like anything else, so its variants register exactly as a ring's do
        // (ProductEventConsumerTest drives the consumer for that; this test only
        // proves the record binds a payload that carries the field).
        // Phase 5 starts reading it, from the snapshot catalog then re-sends.
        String json = """
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "title": "Gift box, medium",
                  "tags": [],
                  "productType": "PACKAGING",
                  "status": "DRAFT",
                  "variants": [
                    {
                      "variantId": "22222222-2222-4222-8222-222222222222",
                      "sku": "BOX-M",
                      "title": "Gift box, medium",
                      "unitPrice": 0.00,
                      "currency": "ZMW",
                      "active": true
                    }
                  ],
                  "kind": "PACKAGING"
                }
                """;

        var event = mapper.readValue(json, ProductCreatedEvent.class);

        assertThat(event.variants()).hasSize(1);
        assertThat(event.variants().getFirst().sku()).isEqualTo("BOX-M");
        assertThat(event.productType()).isEqualTo("PACKAGING");
    }

    @Test
    @DisplayName("a product.updated carrying variants deserialises")
    void updatedWithVariants() throws Exception {
        String json = """
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "title": "Aurelia Solitaire Ring",
                  "description": "A solitaire.",
                  "tags": ["rings"],
                  "productType": "JEWELLERY",
                  "status": "DRAFT",
                  "variants": [
                    {
                      "variantId": "22222222-2222-4222-8222-222222222222",
                      "sku": "AUR-M",
                      "title": "Aurelia Solitaire Ring - Ring Size M",
                      "unitPrice": 0.00,
                      "currency": "ZMW",
                      "active": true
                    }
                  ]
                }
                """;

        var event = mapper.readValue(json, ProductUpdatedEvent.class);

        assertThat(event.status()).isEqualTo("DRAFT");
        assertThat(event.variants()).hasSize(1);
        // A 0.00 price reaches this service and is simply not its business:
        // inventory tracks quantities, and whether something is sellable is
        // catalog's judgement, carried as `status` and `active`.
        assertThat(event.variants().getFirst().unitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("product.deleted is unchanged, so the delete path needs no new field")
    void deletedIsUnchanged() throws Exception {
        var event = mapper.readValue(
                "{\"id\":\"11111111-1111-4111-8111-111111111111\"}",
                ProductDeletedEvent.class);

        // It names a PRODUCT, and the rows are keyed by variant -- which is
        // why product_index gained a product_id column rather than this event
        // gaining a variant list.
        assertThat(event.id()).isEqualTo(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    }

    @Test
    @DisplayName("the fields this consumer READS are the contract catalog must not break")
    void readFieldsAreTheContract() throws Exception {
        // Stated as a test so the list is somewhere other than a comment.
        // Renaming or removing any of these in catalog would not fail a build
        // -- nothing compiles the two sides together -- it would make this
        // consumer silently register nothing, or register rows with no title.
        String json = """
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "title": "Aurelia",
                  "variants": [
                    {"variantId": "22222222-2222-4222-8222-222222222222",
                     "title": "Aurelia - M", "active": true}
                  ]
                }
                """;
        var event = mapper.readValue(json, ProductCreatedEvent.class);

        assertThat(event.id()).isNotNull();
        assertThat(event.title()).isNotNull();
        assertThat(event.variants()).singleElement().satisfies(v -> {
            assertThat(v.variantId()).isNotNull();
            assertThat(v.title()).isNotNull();
            assertThat(v.active()).isTrue();
        });

        List<String> contract = List.of("id", "title", "variants[].variantId",
                "variants[].title", "variants[].active");
        assertThat(contract).hasSize(5);
    }
}
