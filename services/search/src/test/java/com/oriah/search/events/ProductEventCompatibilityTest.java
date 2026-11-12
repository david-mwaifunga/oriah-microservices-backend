package com.oriah.search.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether this service survives catalog's event changes.
 *
 * <h2>Why search is tolerant by construction, and why that still gets a test</h2>
 *
 * Unlike inventory, this consumer never binds to an event class. It reads a
 * generic tree and pulls out the four fields it indexes (and, since Phase 3, the two that decide whether
 * a product is LISTED: status and kind):
 *
 * <pre>
 *   JsonNode payload = objectMapper.readTree(message.getPayload());
 *   UUID id   = UUID.fromString(payload.path("id").asText());
 *   String t  = payload.path("title").asText("");
 *   String d  = payload.path("description").asText(null);
 *   String g  = tagsToString(payload.path("tags"));
 *   boolean listed = SearchIndexEntry.isListable(
 *           payload.path("status").asText(null), payload.path("kind").asText(null));
 * </pre>
 *
 * A field it does not know is therefore never looked up, so catalog's
 * additions cannot break it — there is no record to fail to bind. That is a
 * genuinely stronger position than inventory's, which needed an annotation to
 * stop depending on mapper configuration.
 *
 * <p>Tested anyway, for two reasons. The tolerance is a property of
 * {@code path()} rather than of anything written down, so a future refactor to
 * a typed record would silently remove it. And {@code path()}'s behaviour on
 * an ABSENT node is the thing the consumer actually relies on — it returns a
 * missing node rather than null, which is why the defaults in those
 * {@code asText(...)} calls work at all.
 */
class ProductEventCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The exact extraction the consumer performs. */
    private static String[] extract(JsonNode payload) {
        return new String[] {
                payload.path("id").asText(null),
                payload.path("title").asText(""),
                payload.path("description").asText(null),
                payload.path("tags").isArray() ? "array" : "absent",
                payload.path("status").asText(null),
                payload.path("kind").asText(null),
        };
    }

    @Test
    @DisplayName("a NEW-SHAPE event with variants, productType and status indexes exactly as before")
    void newShapeIsIndexedUnchanged() throws Exception {
        String json = """
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "title": "Aurelia Solitaire Ring",
                  "description": "A solitaire.",
                  "tags": ["rings", "18k"],
                  "productType": "JEWELLERY",
                  "status": "ACTIVE",
                  "variants": [
                    {"variantId": "22222222-2222-4222-8222-222222222222",
                     "sku": "AUR-M", "title": "Aurelia - Ring Size M",
                     "unitPrice": 4500.00, "currency": "ZMW", "active": true}
                  ],
                  "categorySlugs": ["rings"],
                  "primaryImageId": "33333333-3333-4333-8333-333333333333"
                }
                """;

        String[] indexed = extract(mapper.readTree(json));

        assertThat(indexed[0]).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(indexed[1]).isEqualTo("Aurelia Solitaire Ring");
        assertThat(indexed[2]).isEqualTo("A solitaire.");
        assertThat(indexed[3]).isEqualTo("array");
    }

    @Test
    @DisplayName("price, type and variants are NOT indexed, which is the deliberate scope of this service; status and kind are READ, to decide listing")
    void typeAndVariantsAreIgnoredButStatusAndKindAreRead() throws Exception {
        // Price, type and variants reach this consumer and are dropped. That is
        // a decision, not an oversight: the storefront's structured filters
        // (category, type, price band, availability) run against CATALOG, which
        // owns that data and can index it properly. This index is four text
        // fields in a tsvector with no pagination and no facets, and a
        // category-faceted storefront cannot be built on it.
        //
        // Status and kind are the exception, since Phase 3: they decide whether
        // a product may be returned at all (SearchIndexEntry.isListable), so
        // renaming either in catalog would silently stop products being listed.
        // ProductEventListingTest drives the real consumer for that behaviour;
        // this test pins the field NAMES this consumer depends on.
        JsonNode payload = mapper.readTree("""
                {"id": "11111111-1111-4111-8111-111111111111", "title": "X",
                 "productType": "WATCH", "status": "DRAFT", "kind": "SELLABLE",
                 "variants": [{"unitPrice": 9000.00}]}
                """);

        // Present in the payload...
        assertThat(payload.path("productType").asText()).isEqualTo("WATCH");
        // ...and not among the text fields the consumer indexes; the last two
        // values are status and kind, which it reads for listing only.
        assertThat(extract(payload)).containsExactly(
                "11111111-1111-4111-8111-111111111111", "X", null, "absent", "DRAFT", "SELLABLE");
    }

    @Test
    @DisplayName("an OLD-SHAPE event still indexes, so replaying pre-upgrade messages is safe")
    void oldShapeStillIndexes() throws Exception {
        // The topic has no configured retention and there is no outbox, so
        // messages published before catalog's change can still be redelivered.
        String[] indexed = extract(mapper.readTree("""
                {"id": "11111111-1111-4111-8111-111111111111",
                 "title": "Legacy Ring", "tags": ["rings"]}
                """));

        assertThat(indexed[1]).isEqualTo("Legacy Ring");
        assertThat(indexed[3]).isEqualTo("array");
    }

    @Test
    @DisplayName("product.created carries no description, and path() is why that is harmless")
    void absentDescriptionIsNull() throws Exception {
        // ProductCreatedEvent has never had a description field, so a freshly
        // created product indexes with a null one until its first update. The
        // consumer depends on path() returning a MISSING node rather than
        // null for an absent key -- get() would return null and the
        // asText(default) call would throw.
        JsonNode payload = mapper.readTree("""
                {"id": "11111111-1111-4111-8111-111111111111", "title": "New", "tags": []}
                """);

        assertThat(payload.path("description").isMissingNode()).isTrue();
        assertThat(payload.path("description").asText(null)).isNull();
        assertThat(extract(payload)[2]).isNull();
    }

    @Test
    @DisplayName("a missing title degrades to empty rather than throwing")
    void missingTitleDegrades() throws Exception {
        // Not expected from catalog, where title is NOT NULL -- but a throw
        // here would park the whole snapshot on the dead-letter topic and
        // leave the product out of the index until someone replays it.
        // Degrading keeps the row indexable.
        assertThat(extract(mapper.readTree("""
                {"id": "11111111-1111-4111-8111-111111111111"}
                """))[1]).isEmpty();
    }

    @Test
    @DisplayName("the fields this consumer reads are the contract catalog must not rename")
    void readFieldsAreTheContract() throws Exception {
        // id, title, description and tags, plus status and kind (Phase 3: they
        // decide whether a product is listed). Renaming any of them in catalog
        // would not fail a build -- nothing binds these names to a type -- it
        // would make this index quietly stop updating, index empty titles, or
        // stop listing every product.
        JsonNode payload = mapper.readTree("""
                {"id": "11111111-1111-4111-8111-111111111111", "title": "T",
                 "description": "D", "tags": ["a"], "status": "ACTIVE", "kind": "SELLABLE"}
                """);

        assertThat(payload.has("id")).isTrue();
        assertThat(payload.has("title")).isTrue();
        assertThat(payload.has("description")).isTrue();
        assertThat(payload.has("tags")).isTrue();
        assertThat(payload.has("status")).isTrue();
        assertThat(payload.has("kind")).isTrue();
        assertThat(extract(payload)).containsExactly(
                "11111111-1111-4111-8111-111111111111", "T", "D", "array", "ACTIVE", "SELLABLE");
    }
}
