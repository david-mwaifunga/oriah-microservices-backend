package com.oriah.catalog.controller;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductService;
import com.oriah.catalog.service.ProductWithAvailability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService service;

    @Test
    @DisplayName("GET /api/v1/products/{id} returns the product enriched with availability")
    void getReturnsProductByIdWithAvailability() throws Exception {
        var id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var aurelia = new Product(id, "Aurelia Solitaire Ring",
                "A single-stone solitaire in 18k recycled gold.",
                List.of("rings", "engagement"));
        var enriched = new ProductWithAvailability(aurelia, Availability.LOW_STOCK);

        when(service.findByIdWithAvailability(id)).thenReturn(Optional.of(enriched));

        mvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Aurelia Solitaire Ring"))
                .andExpect(jsonPath("$.description").value("A single-stone solitaire in 18k recycled gold."))
                .andExpect(jsonPath("$.tags[0]").value("rings"))
                .andExpect(jsonPath("$.tags[1]").value("engagement"))
                .andExpect(jsonPath("$.availability").value("LOW_STOCK"));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} returns 404 when the product does not exist")
    void getReturns404WhenNotFound() throws Exception {
        var id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(service.findByIdWithAvailability(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/products returns a paginated page of products with no filter")
    void listReturnsPagedProducts() throws Exception {
        var aurelia = new Product(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Aurelia", null, List.of("rings"));
        var lume = new Product(UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Lume", "Pendant in 14k gold.", List.of("necklaces"));

        Page<Product> page = new PageImpl<>(List.of(aurelia, lume), PageRequest.of(0, 20), 2);
        when(service.findAll(isNull(), any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Aurelia"))
                .andExpect(jsonPath("$.content[1].description").value("Pendant in 14k gold."))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("GET /api/v1/products returns an empty page when no products exist")
    void listReturnsEmptyPageWhenNoProducts() throws Exception {
        Page<Product> empty = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0);
        when(service.findAll(isNull(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/products?tag=rings filters by tag")
    void listFilteredByTag() throws Exception {
        var aurelia = new Product(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Aurelia", null, List.of("rings"));

        Page<Product> page = new PageImpl<>(List.of(aurelia), PageRequest.of(0, 20), 1);
        when(service.findAll(eq("rings"), any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/api/v1/products").param("tag", "rings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Aurelia"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/products honors page and size query parameters")
    void listHonorsPagingParameters() throws Exception {
        Page<Product> empty = new PageImpl<>(Collections.emptyList(), PageRequest.of(2, 5), 12);
        when(service.findAll(isNull(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/api/v1/products").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(3));
    }
}
