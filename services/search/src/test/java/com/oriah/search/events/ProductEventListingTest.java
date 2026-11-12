package com.oriah.search.events;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.oriah.search.TestcontainersConfiguration;
import com.oriah.search.repository.SearchIndexRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the search index LISTS. Each product event is a whole snapshot of the
 * product, and the index keeps a row for every product it has been told about,
 * but only a product that is ACTIVE and SELLABLE may ever come back from a
 * search: a draft is unfinished and packaging is never sold.
 *
 * <p>Until Phase 3 the index had no status at all, so a DRAFT product was
 * full-text searchable. These tests are written against the consumer directly,
 * the way catalog's relay sends it, so that what the index lists is decided by
 * what the snapshot says and nothing else.
 *
 * <p>The rule, in one place: <b>listed = status is ACTIVE and kind is SELLABLE</b>,
 * where a snapshot with no {@code kind} is read as SELLABLE (everything catalog
 * held before Phase 3 was made to be sold) and anything this build does not
 * recognise is not listed. An absent or unknown status is not listed either: if
 * the index cannot tell, it says nothing.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=productEvents",
        "spring.cloud.stream.bindings.productEvents-in-0.destination=catalog.product"
})
class ProductEventListingTest {

    @Autowired
    private InputDestination input;
    @Autowired
    private SearchIndexRepository index;

    private void send(String type, UUID productId, long seq, String payload) {
        input.send(Relayed.productEvent(type, productId, seq, payload).build(), "catalog.product");
    }

    private static String snapshot(UUID id, String title, String statusAndKind) {
        return "{\"id\":\"%s\",\"title\":\"%s\",\"description\":null,\"tags\":[]%s}"
                .formatted(id, title, statusAndKind);
    }

    private boolean isListed(UUID id) {
        return index.findById(id).orElseThrow().listed();
    }

    @Test
    @DisplayName("a DRAFT sellable product is indexed but not listed")
    void draftIsNotListed() {
        UUID id = UUID.randomUUID();
        send("product.created", id, 1, snapshot(id, "Quartzite Pendant", ",\"status\":\"DRAFT\",\"kind\":\"SELLABLE\""));

        assertThat(isListed(id)).isFalse();
        assertThat(index.search("quartzite", 10)).isEmpty();
    }

    @Test
    @DisplayName("an ACTIVE sellable product is listed and found")
    void activeSellableIsListed() {
        UUID id = UUID.randomUUID();
        send("product.created", id, 1, snapshot(id, "Tourmaline Pendant", ",\"status\":\"ACTIVE\",\"kind\":\"SELLABLE\""));

        assertThat(isListed(id)).isTrue();
        assertThat(index.search("tourmaline", 10)).extracting(entry -> entry.variantId()).containsExactly(id);
    }

    @Test
    @DisplayName("publishing lists a product and withdrawing it takes it out again")
    void publishAndWithdrawFlipTheListing() {
        UUID id = UUID.randomUUID();
        String draft = snapshot(id, "Garnet Bracelet", ",\"status\":\"DRAFT\",\"kind\":\"SELLABLE\"");
        String active = snapshot(id, "Garnet Bracelet", ",\"status\":\"ACTIVE\",\"kind\":\"SELLABLE\"");

        send("product.created", id, 1, draft);
        assertThat(isListed(id)).isFalse();
        send("product.updated", id, 2, active);
        assertThat(isListed(id)).isTrue();
        send("product.updated", id, 3, draft);
        assertThat(isListed(id)).isFalse();
        assertThat(index.search("garnet", 10)).isEmpty();
    }

    @Test
    @DisplayName("packaging is never listed, even in a snapshot that says ACTIVE")
    void packagingIsNeverListed() {
        UUID id = UUID.randomUUID();
        send("product.created", id, 1, snapshot(id, "Sapphire Gift Box", ",\"status\":\"ACTIVE\",\"kind\":\"PACKAGING\""));

        assertThat(isListed(id)).isFalse();
        assertThat(index.search("sapphire", 10)).isEmpty();
    }

    @Test
    @DisplayName("an ARCHIVED product is not listed")
    void archivedIsNotListed() {
        UUID id = UUID.randomUUID();
        send("product.created", id, 1, snapshot(id, "Amber Anklet", ",\"status\":\"ARCHIVED\",\"kind\":\"SELLABLE\""));

        assertThat(isListed(id)).isFalse();
    }

    @Test
    @DisplayName("a snapshot with no kind (written before Phase 3) is read as sellable")
    void absentKindIsSellable() {
        UUID id = UUID.randomUUID();
        send("product.created", id, 1, snapshot(id, "Peridot Ring", ",\"status\":\"ACTIVE\""));

        assertThat(isListed(id)).isTrue();
    }

    @Test
    @DisplayName("a kind this build does not know is not listed")
    void unknownKindIsNotListed() {
        UUID id = UUID.randomUUID();
        send("product.created", id, 1, snapshot(id, "Jasper Gift Card", ",\"status\":\"ACTIVE\",\"kind\":\"VOUCHER\""));

        assertThat(isListed(id)).isFalse();
    }

    @Test
    @DisplayName("a JSON null kind is read as absent (sellable) and a JSON null status as absent (not listed)")
    void jsonNullsAreReadAsAbsent() {
        UUID nullKind = UUID.randomUUID();
        UUID nullStatus = UUID.randomUUID();
        send("product.created", nullKind, 1, snapshot(nullKind, "Moonstone Ring", ",\"status\":\"ACTIVE\",\"kind\":null"));
        send("product.created", nullStatus, 1, snapshot(nullStatus, "Spinel Ring", ",\"status\":null,\"kind\":\"SELLABLE\""));

        assertThat(isListed(nullKind)).isTrue();
        assertThat(isListed(nullStatus)).isFalse();
    }

    @Test
    @DisplayName("a snapshot with no status, or one this build does not know, is not listed")
    void absentOrUnknownStatusIsNotListed() {
        UUID none = UUID.randomUUID();
        UUID odd = UUID.randomUUID();
        send("product.created", none, 1, snapshot(none, "Onyx Studs", ",\"kind\":\"SELLABLE\""));
        send("product.created", odd, 1, snapshot(odd, "Agate Studs", ",\"status\":\"PENDING\",\"kind\":\"SELLABLE\""));

        assertThat(isListed(none)).isFalse();
        assertThat(isListed(odd)).isFalse();
    }

    @Test
    @DisplayName("an older snapshot arriving late cannot list a product that a newer one withdrew")
    void staleSnapshotCannotRelist() {
        UUID id = UUID.randomUUID();
        send("product.created", id, 1, snapshot(id, "Topaz Brooch", ",\"status\":\"DRAFT\",\"kind\":\"SELLABLE\""));
        send("product.updated", id, 3, snapshot(id, "Topaz Brooch", ",\"status\":\"DRAFT\",\"kind\":\"SELLABLE\""));
        // seq 2 was ACTIVE, and is only now delivered.
        send("product.updated", id, 2, snapshot(id, "Topaz Brooch", ",\"status\":\"ACTIVE\",\"kind\":\"SELLABLE\""));

        assertThat(isListed(id)).isFalse();
    }
}
