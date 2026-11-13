package com.oriah.catalog.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the bytes catalog publishes on {@code catalog.product}, exactly.
 *
 * <h2>Why golden files</h2>
 *
 * Inventory and search read these payloads, and Phase 1 moves how they are
 * sent (from a send after commit to the outbox and its relay) without
 * changing what they say. A field renamed, a null left out, or a price
 * written as {@code 4.6005E+3} instead of {@code 4600.50} would break a
 * consumer while every test that reads the payload back into a Java record
 * still passed. So the payload is compared as TEXT against a file captured
 * from the producer as it stood before the move.
 *
 * <p>The files were written while the old send-after-commit bridge was still
 * the publisher, and this test passed against it: that is what makes them
 * today's wire format rather than a guess at it. They had to keep passing,
 * unchanged, when the outbox took over carrying the events. Since then the
 * format has grown once, additively, and the files with it: Phase 3 appended
 * {@code "kind"} as the last field of product.created and product.updated.
 *
 * <h2>Why through HTTP</h2>
 *
 * The admin endpoints are the one surface that does not change shape when the
 * service starts taking the caller as the event's actor, so this test needs
 * no edit across that change either. Messages are matched to this test's
 * product by the payload's {@code id}, and waited for, because a publisher
 * that polls (the relay) delivers a moment after the request returns.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProductEventGoldenTest {

    static final String TOPIC = "catalog.product";

    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination output;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a product's whole life publishes exactly today's payloads, with today's event-type headers")
    void aProductsLifePublishesTodaysPayloads() throws Exception {
        String productId = createProduct();
        assertNext(productId, "product.created", "product.created.json", Map.of("productId", productId));

        mvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"AUR-1\"}"))
                .andExpect(status().isCreated());
        String variantId = firstVariantId(productId);
        Map<String, String> ids = Map.of("productId", productId, "variantId", variantId);
        assertNext(productId, "product.updated", "product.updated.variant-added.json", ids);

        // The one way to a price: the pricing endpoint, as staff who may set prices and see what stock cost. Nothing is held, so no cost is known.
        mvc.perform(put("/api/v1/admin/pricing/variants/" + variantId + "/price")
                        .with(jwt().jwt(token -> token.subject("golden-admin"))
                                .authorities(CATALOG_ADMIN, new SimpleGrantedAuthority("ROLE_COST_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":4600.50,\"expectedPriceId\":null}"))
                .andExpect(status().isOk());
        assertNext(productId, "product.updated", "product.updated.repriced.json", ids);

        mvc.perform(post("/api/v1/admin/products/" + productId + "/publish").with(admin()))
                .andExpect(status().isOk());
        assertNext(productId, "product.updated", "product.updated.published.json", ids);

        mvc.perform(patch("/api/v1/admin/products/" + productId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Aurelia Ring\"}"))
                .andExpect(status().isOk());
        assertNext(productId, "product.updated", "product.updated.renamed.json", ids);

        mvc.perform(delete("/api/v1/admin/products/" + productId).with(admin()))
                .andExpect(status().isNoContent());
        assertNext(productId, "product.deleted", "product.deleted.json", Map.of("productId", productId));
    }

    /**
     * Phase 3 appended {@code kind} as the last field of product.created and
     * product.updated -- additive, so a consumer that does not know it is
     * unaffected, and one that does (search, and inventory from Phase 5) can
     * tell packaging from something for sale. An entry made as packaging says
     * so from its first event.
     */
    @Test
    @DisplayName("an entry made as packaging says PACKAGING in every event, from the first")
    void packagingSaysSoInEveryEvent() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Gift box, medium\",\"kind\":\"PACKAGING\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        assertNext(productId, "product.created", "product.created.packaging.json", Map.of("productId", productId));

        mvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BOX-M\",\"unitPrice\":0.00}"))
                .andExpect(status().isCreated());
        String variantId = firstVariantId(productId);
        assertNext(productId, "product.updated", "product.updated.packaging-variant-added.json",
                Map.of("productId", productId, "variantId", variantId));
    }

    // ------------------------------------------------------------- helpers

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("golden-admin")).authorities(CATALOG_ADMIN);
    }

    /** No description, on purpose: every product.updated then pins "description":null. */
    private String createProduct() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Aurelia Solitaire Ring\",\"tags\":[\"rings\",\"gold\"]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private String firstVariantId(String productId) throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/admin/products/" + productId).with(admin()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("variants").get(0).get("id").asText();
    }

    private void assertNext(String productId, String eventType, String fixture, Map<String, String> ids)
            throws IOException {
        Message<byte[]> message = nextFor(output, objectMapper, productId);
        assertThat(message.getHeaders()).containsEntry("event-type", eventType);
        assertThat(new String(message.getPayload(), StandardCharsets.UTF_8))
                .as("the %s payload, byte for byte, against %s", eventType, fixture)
                .isEqualTo(golden(fixture, ids));
    }

    /**
     * The next message on {@value #TOPIC} about this product, skipping any
     * about others (another test in the same context may have left some).
     */
    static Message<byte[]> nextFor(OutputDestination output, ObjectMapper mapper, String productId)
            throws IOException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Message<byte[]> message = output.receive(250, TOPIC);
            if (message != null && productId.equals(mapper.readTree(message.getPayload()).path("id").asText())) {
                return message;
            }
        }
        throw new AssertionError("no message about product " + productId + " on " + TOPIC + " within 10s");
    }

    /** A golden file with this run's ids put in place of its {{placeholders}}. */
    static String golden(String fixture, Map<String, String> ids) throws IOException {
        try (InputStream in = ProductEventGoldenTest.class.getResourceAsStream("/events/golden/" + fixture)) {
            assertThat(in).as("golden file %s", fixture).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> id : ids.entrySet()) {
                text = text.replace("{{" + id.getKey() + "}}", id.getValue());
            }
            return text;
        }
    }
}
