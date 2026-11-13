package com.oriah.catalog.events;

import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog admin's writes, over HTTP, as one named member of staff.
 *
 * <p>The event tests drive catalog through its endpoints rather than its
 * service so the token's subject travels the real path to the event's actor:
 * controller, service, outbox row, published header.
 */
final class AdminWrites {

    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final String subject;

    AdminWrites(MockMvc mvc, ObjectMapper mapper, String subject) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.subject = subject;
    }

    RequestPostProcessor token() {
        return jwt().jwt(jwt -> jwt.subject(subject)).authorities(CATALOG_ADMIN);
    }

    /** @return the new product's id */
    String create(String json) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products")
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    ResultActions addVariant(String productId, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    /**
     * A price, set the only way it can be: the pricing endpoint, as a member of staff who may both set prices and see what stock cost
     * (CATALOG_ADMIN and COST_VIEWER). Inventory is the shared stand-in, which holds nothing unless a test says otherwise.
     */
    ResultActions price(String variantId, String json) throws Exception {
        return mvc.perform(put("/api/v1/admin/pricing/variants/" + variantId + "/price")
                .with(jwt().jwt(jwt -> jwt.subject(subject)).authorities(CATALOG_ADMIN, new SimpleGrantedAuthority("ROLE_COST_VIEWER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    ResultActions publish(String productId) throws Exception {
        return mvc.perform(post("/api/v1/admin/products/" + productId + "/publish").with(token()));
    }

    ResultActions update(String productId, String json) throws Exception {
        return mvc.perform(patch("/api/v1/admin/products/" + productId)
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    ResultActions remove(String productId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1/admin/products/" + productId).with(token()));
    }

    /** The id of the product's variant at this position, as the admin read lists them. */
    String variantId(String productId, int position) throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/admin/products/" + productId).with(token()))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString())
                .get("variants").get(position).get("id").asText();
    }
}
