package com.oriah.catalog.product;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminProductController.class)
class AdminProductControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService service;

    @Test
    @DisplayName("POST /api/v1/admin/products creates a product and returns 201 with Location header")
    void createReturns201WithLocationHeader() throws Exception {
        var id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        var saved = new Product(id, "Aurelia Solitaire Ring",
                "A single-stone solitaire in 18k recycled gold.",
                List.of("rings", "engagement"));

        when(service.create(eq("Aurelia Solitaire Ring"),
                eq("A single-stone solitaire in 18k recycled gold."),
                any()))
                .thenReturn(saved);

        String body = """
                {
                  "title": "Aurelia Solitaire Ring",
                  "description": "A single-stone solitaire in 18k recycled gold.",
                  "tags": ["rings", "engagement"]
                }
                """;

        mvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/products/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andExpect(jsonPath("$.tags[0]").value("rings"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/products returns 400 when title is blank")
    void createWithBlankTitleReturns400() throws Exception {
        String body = """
                {
                  "title": "",
                  "description": "A pendant.",
                  "tags": []
                }
                """;

        mvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/admin/products returns 400 when title exceeds max size")
    void createWithTooLongTitleReturns400() throws Exception {
        String oversizedTitle = "x".repeat(201);
        String body = """
                {
                  "title": "%s",
                  "description": "A pendant.",
                  "tags": []
                }
                """.formatted(oversizedTitle);

        mvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any(), any(), any());
    }
}
