package com.oriah.search.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.search.TestcontainersConfiguration;
import com.oriah.search.events.Relayed;
import com.oriah.search.repository.SearchIndexRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: feed Kafka product.* events through the test binder,
 * then query the search index over HTTP. Proves the full pipeline
 * (consumer -> Postgres tsvector -> search query) works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=productEvents",
        "spring.cloud.stream.bindings.productEvents-in-0.destination=catalog.product"
})
class SearchIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private InputDestination inputDestination;
    @Autowired
    private SearchIndexRepository repository;
    @Autowired
    private ObjectMapper objectMapper;

    private static final UUID R1 = UUID.randomUUID();
    private static final UUID R2 = UUID.randomUUID();
    private static final UUID R3 = UUID.randomUUID();
    private static final AtomicLong SEQ = new AtomicLong();

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("product.created events populate the index; /search?q=... finds them")
    void indexAndSearch() throws Exception {
        sendCreated(R1, "Aurelia Solitaire", "18k gold band", List.of("rings", "gold"));
        sendCreated(R2, "Lume Moonstone Necklace", "iridescent pendant", List.of("necklaces"));
        sendCreated(R3, "Cassini Earrings", "drop earrings", List.of("earrings", "gold"));

        mvc.perform(get("/api/v1/search?q=gold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mvc.perform(get("/api/v1/search?q=moonstone"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.hits[0].title").value("Lume Moonstone Necklace"));
    }

    @Test
    @DisplayName("product.deleted removes the row from the index")
    void deletedRemoves() throws Exception {
        sendCreated(R1, "Aurelia", "x", List.of("rings"));
        sendDeleted(R1);
        mvc.perform(get("/api/v1/search?q=aurelia"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("product.updated replaces the row content")
    void updatedReplaces() throws Exception {
        sendCreated(R1, "Aurelia Original", "x", List.of("rings"));
        sendUpdated(R1, "Aurelia Renamed", "new desc", List.of("rings", "limited"));
        mvc.perform(get("/api/v1/search?q=renamed"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.hits[0].title").value("Aurelia Renamed"));
        mvc.perform(get("/api/v1/search?q=limited"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("empty/blank query returns count=0 (no SQL execution)")
    void emptyQuery() throws Exception {
        mvc.perform(get("/api/v1/search?q="))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("anonymous (no JWT) is allowed for /search")
    void anonymous() throws Exception {
        sendCreated(R1, "Aurelia", "x", List.of("rings"));
        mvc.perform(get("/api/v1/search?q=aurelia"))
                .andExpect(status().isOk());
    }

    private void sendCreated(UUID id, String title, String description, List<String> tags) throws Exception {
        send("product.created", id, Map.of("id", id.toString(), "title", title, "tags", tags));
    }

    private void sendUpdated(UUID id, String title, String description, List<String> tags) throws Exception {
        send("product.updated", id, Map.of(
                "id", id.toString(), "title", title,
                "description", description, "tags", tags));
    }

    private void sendDeleted(UUID id) throws Exception {
        send("product.deleted", id, Map.of("id", id.toString()));
    }

    /**
     * Sends as catalog's relay does, with the envelope the Inbox requires. The
     * seq only ever rises, as catalog's does per product: R1..R3 are shared by
     * every test here and the newest seq applied per product outlives the
     * index rows cleared before each test, so a test starting again from 1
     * would have its snapshots skipped as old.
     */
    private void send(String type, UUID productId, Map<String, Object> payload) throws Exception {
        inputDestination.send(
                Relayed.productEvent(type, productId, SEQ.incrementAndGet(),
                        objectMapper.writeValueAsString(payload)).build(),
                "catalog.product"
        );
    }

    @Test
    @DisplayName("every hit carries the PRODUCT id in both productId and the deprecated variantId")
    void bothIdFieldsCarryTheProductId() throws Exception {
        // The deprecation guard. This index holds one row per PRODUCT; its
        // column is named variant_id and the wire field was variantId, which
        // was accurate only while a product had exactly one variant whose id
        // was the product's own. The VALUE was never wrong -- the NAME stopped
        // being defensible once catalog grew real variants.
        //
        // So productId is the field to use and variantId carries the identical
        // value until the frontend stops reading it. This assertion is what
        // keeps that true: if this index ever started holding one row per
        // variant, variantId would begin carrying something genuinely
        // different, and a client still reading it would link to the wrong
        // page with nothing failing anywhere.
        sendCreated(UUID.randomUUID(), "Aurelia Solitaire Ring",
                "A solitaire in 18k gold.", List.of("rings"));

        mvc.perform(get("/api/v1/search").param("q", "solitaire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.hits[0].productId").exists())
                .andExpect(jsonPath("$.hits[0].variantId").exists())
                // Identical, asserted against each other rather than against a
                // literal, so the test does not need to know which id was
                // generated.
                .andExpect(result -> {
                    var body = result.getResponse().getContentAsString();
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var hit = mapper.readTree(body).get("hits").get(0);
                    org.assertj.core.api.Assertions
                            .assertThat(hit.get("productId").asText())
                            .as("variantId is a deprecated alias of productId for this window")
                            .isEqualTo(hit.get("variantId").asText());
                });
    }
}
