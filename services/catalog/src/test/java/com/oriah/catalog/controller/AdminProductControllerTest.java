package com.oriah.catalog.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.oriah.catalog.config.SecurityConfig;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminProductController.class)
@Import(SecurityConfig.class)
class AdminProductControllerTest {

    /**
     * Helper to attach a JWT with the CATALOG_ADMIN role to a MockMvc
     * request. {@code jwt()} comes from spring-security-test and skips
     * the real {@code JwtDecoder} entirely — the authentication object
     * is constructed in-process and placed straight into the security
     * context.
     */
    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService service;

    /**
     * Required because Spring Security's OAuth2 resource server
     * auto-configuration registers a {@code JwtDecoder} bean that, at
     * context startup, tries to fetch JWKS from the configured
     * issuer-uri. With no live Keycloak in tests, that fetch fails and
     * the context can't start. Replacing the bean with a mock makes the
     * resource server happy at startup; the {@code jwt()} post-processor
     * bypasses the decoder entirely on each request, so the mock is
     * never actually invoked.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

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
                        .with(jwt().authorities(CATALOG_ADMIN))
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
                        .with(jwt().authorities(CATALOG_ADMIN))
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
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/products/{id} updates the title and returns 200")
    void updateChangesTitle() throws Exception {
        var id = UUID.fromString("44444444-4444-4444-4444-444444444444");
        var updated = new Product(id, "Aurelia Renamed",
                "A single-stone solitaire in 18k recycled gold.",
                List.of("rings", "engagement"));

        when(service.update(eq(id), any(ProductUpdateRequest.class)))
                .thenReturn(Optional.of(updated));

        String body = """
                {"title": "Aurelia Renamed"}
                """;

        mvc.perform(patch("/api/v1/admin/products/{id}", id)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Aurelia Renamed"));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/products/{id} returns 400 when title is blank")
    void updateRejectsBlankTitle() throws Exception {
        var id = UUID.fromString("55555555-5555-5555-5555-555555555555");
        String body = """
                {"title": ""}
                """;

        mvc.perform(patch("/api/v1/admin/products/{id}", id)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/products/{id} returns 400 when title exceeds max size")
    void updateRejectsTooLongTitle() throws Exception {
        var id = UUID.fromString("66666666-6666-6666-6666-666666666666");
        String oversizedTitle = "x".repeat(201);
        String body = """
                {"title": "%s"}
                """.formatted(oversizedTitle);

        mvc.perform(patch("/api/v1/admin/products/{id}", id)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/products/{id} returns 404 when the product does not exist")
    void updateReturns404WhenNotFound() throws Exception {
        var id = UUID.fromString("77777777-7777-7777-7777-777777777777");
        when(service.update(eq(id), any(ProductUpdateRequest.class)))
                .thenReturn(Optional.empty());

        String body = """
                {"title": "Anything"}
                """;

        mvc.perform(patch("/api/v1/admin/products/{id}", id)
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/products/{id} returns 204 when the product exists")
    void deleteReturns204OnExistingProduct() throws Exception {
        var id = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(service.deleteById(id)).thenReturn(true);

        mvc.perform(delete("/api/v1/admin/products/{id}", id)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/products/{id} returns 404 when the product does not exist")
    void deleteReturns404WhenNotFound() throws Exception {
        var id = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(service.deleteById(id)).thenReturn(false);

        mvc.perform(delete("/api/v1/admin/products/{id}", id)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST without a JWT returns 401 — and never reaches the service")
    void createWithoutAuthReturns401() throws Exception {
        mvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Aurelia", "description": null, "tags": []}
                                """))
                .andExpect(status().isUnauthorized());

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("POST with a JWT lacking CATALOG_ADMIN role returns 403")
    void createWithWrongRoleReturns403() throws Exception {
        mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Aurelia", "description": null, "tags": []}
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).create(any(), any(), any());
    }
}
