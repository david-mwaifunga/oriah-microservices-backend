package com.oriah.catalog.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The boxes a salesperson can choose from when a sale is recorded (Phase 9, CLIENT_DECISIONS 4.2: the box is proposed from the product's default and the person
 * picks another when it has run out). Identity only: the packaging that exists and is in use, by name and SKU, never a price and never a cost (Q29). Internal, like
 * the other variant reads: the order service asks with the person's own token and the gateway has no route for it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PackagingListHttpTest {

    private static final String PATH = "/api/v1/admin/variants/packaging";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(token -> token.subject("kc-" + role)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private String createBox(String title, String sku) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products").with(as("CATALOG_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"kind\":\"PACKAGING\",\"optionNames\":[\"Size\"]}")).andExpect(status().isCreated()).andReturn();
        String product = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        MvcResult added = mvc.perform(post("/api/v1/admin/products/" + product + "/variants").with(as("CATALOG_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"" + sku + "\",\"options\":{\"Size\":\"Large\"}}")).andExpect(status().isCreated()).andReturn();
        JsonNode variants = mapper.readTree(added.getResponse().getContentAsString()).get("variants");
        return variants.get(variants.size() - 1).get("id").asText();
    }

    private String createRing(String title) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products").with(as("CATALOG_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}")).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode list(RequestPostProcessor who) throws Exception {
        MvcResult result = mvc.perform(get(PATH).with(who)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the packaging in use is listed by name and SKU, and nothing else is: no ring, no price, no cost")
    void theBoxesAreListed() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        String box = createBox("Velvet box " + token, "BOX-" + token);
        createRing("Ring " + token);

        MvcResult result = mvc.perform(get(PATH).with(as("SALES_AGENT"))).andExpect(status().isOk()).andReturn();
        JsonNode boxes = mapper.readTree(result.getResponse().getContentAsString());

        JsonNode mine = null;
        for (JsonNode b : boxes) {
            assertThat(b.get("title").asText()).as("only packaging").doesNotStartWith("Ring ");
            if (box.equals(b.get("variantId").asText())) {
                mine = b;
            }
        }
        assertThat(mine).as("the new box is listed").isNotNull();
        assertThat(mine.get("title").asText()).isEqualTo("Velvet box " + token);
        assertThat(mine.get("variantTitle").asText()).contains("Velvet box " + token);
        assertThat(mine.get("sku").asText()).isEqualTo("BOX-" + token);
        String text = result.getResponse().getContentAsString().toLowerCase();
        assertThat(text).doesNotContain("price").doesNotContain("cost").doesNotContain("margin").doesNotContain("landed");
    }

    @Test
    @DisplayName("who may read it: SALES_AGENT (who chooses), FULFILMENT_AGENT (who packs) and CATALOG_ADMIN; a shopper and a bookkeeper are 403 and no token is 401")
    void whoMayRead() throws Exception {
        for (String role : new String[] {"SALES_AGENT", "FULFILMENT_AGENT", "CATALOG_ADMIN"}) {
            assertThat(list(as(role)).isArray()).as(role).isTrue();
        }
        for (String role : new String[] {"USER", "FINANCE_ADMIN", "COST_VIEWER"}) {
            mvc.perform(get(PATH).with(as(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
