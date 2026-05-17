package com.oriah.catalog.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService service;

    @Test
    @DisplayName("GET /api/v1/products/{id} returns the product as JSON")
    void getReturnsProductById() throws Exception {
        var id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var aurelia = new Product(id, "Aurelia Solitaire Ring",
                "A single-stone solitaire in 18k recycled gold.",
                List.of("rings", "engagement"));

        when(service.findById(id)).thenReturn(Optional.of(aurelia));

        mvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andExpect(jsonPath("$.description").value("A single-stone solitaire in 18k recycled gold."))
                .andExpect(jsonPath("$.tags[0]").value("rings"))
                .andExpect(jsonPath("$.tags[1]").value("engagement"));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} returns 404 when the product does not exist")
    void getReturns404WhenNotFound() throws Exception {
        var id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(service.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/products returns all products as a JSON array")
    void listReturnsAllProducts() throws Exception {
        var aurelia = new Product(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Aurelia", null, List.of("rings"));
        var lume = new Product(UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Lume", "Pendant in 14k gold.", List.of("necklaces"));

        when(service.findAll()).thenReturn(List.of(aurelia, lume));

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Aurelia"))
                .andExpect(jsonPath("$[1].title").value("Lume"))
                .andExpect(jsonPath("$[1].description").value("Pendant in 14k gold."));
    }

    @Test
    @DisplayName("GET /api/v1/products returns an empty array when no products exist")
    void listReturnsEmptyArrayWhenNoProducts() throws Exception {
        when(service.findAll()).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
