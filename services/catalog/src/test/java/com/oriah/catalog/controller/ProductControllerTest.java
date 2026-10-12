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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;

import com.oriah.observability.ObservabilityAutoConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.oriah.catalog.config.SecurityConfig;
import com.oriah.catalog.dto.ProductResponse;
import com.oriah.catalog.mapper.ProductMapper;
import com.oriah.catalog.model.Availability;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.service.ProductService;
import com.oriah.catalog.service.ProductWithAvailability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@ImportAutoConfiguration(ObservabilityAutoConfiguration.class)
// ^ @WebMvcTest filters auto-configuration to a web-related allowlist, so a
// third-party one is NOT applied -- and since Tier 5 moved the error advice
// out of catalog and into the shared starter's conditional @Bean, this slice
// would otherwise have NO @ControllerAdvice and every 404 would come back with
// an empty body. The three 404-body tests here failed exactly that way.
//
// Imported as the AUTO-CONFIGURATION rather than as @Import(
// ProblemDetailExceptionHandler.class) on purpose: this exercises the real
// wiring path, condition included, so if the back-off condition regresses the
// slice notices. Hand-registering the bean would mask it.
@Import(SecurityConfig.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService service;

    /**
     * Spring Security's OAuth2 resource server auto-config registers a
     * {@code JwtDecoder} that fetches JWKS from issuer-uri at startup.
     * With no Keycloak in tests, that fetch fails. Replacing the bean
     * with a mock keeps the context healthy; we never actually invoke
     * it because public GETs don't go through auth.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("GET /api/v1/products/{id} returns the product enriched with availability")
    void getReturnsProductByIdWithAvailability() throws Exception {
        var id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var aurelia = new Product(id, "Aurelia Solitaire Ring",
                "A single-stone solitaire in 18k recycled gold.",
                List.of("rings", "engagement"));
        // The service maps inside its own transaction now (lazy collections
        // on the wire format made mapping in the controller a
        // LazyInitializationException), so the stub supplies the response.
        var enriched = ProductMapper.toResponse(
                new ProductWithAvailability(aurelia, Availability.LOW_STOCK));

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
    @DisplayName("GET /api/v1/products/{id} returns 404 with an RFC 7807 body when the product does not exist")
    void getReturns404WhenNotFound() throws Exception {
        var id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(service.findByIdWithAvailability(id)).thenReturn(Optional.empty());

        // Strengthened in Tier 5. This test previously asserted the status and
        // nothing else, which is exactly why the defect below survived: the
        // handler used ResponseEntity.notFound().build(), so the 404 carried an
        // EMPTY BODY -- no detail, no problem type, no traceId. 25 sites across
        // the 12 services did the same.
        //
        // An empty 404 is a bad answer in three ways. oriah-web's apiFetch
        // parses application/problem+json and otherwise SYNTHESIZES a message
        // from the bare status, so the user sees generic failure text instead of
        // what went wrong. Nothing correlates the response to a trace, so
        // "product page 404s sometimes" is unanswerable from a screenshot. And
        // it is indistinguishable from a 404 raised by the gateway for an
        // unmatched route -- a missing product and a missing ROUTE looked
        // identical to a client.
        mvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                // The course's ResourceNotFoundException message format, kept
                // verbatim so what you read there maps onto what you own here.
                .andExpect(jsonPath("$.detail")
                        .value("Product not found with the given input data id : '" + id + "'"))
                .andExpect(jsonPath("$.instance").value("/api/v1/products/" + id));
    }

    @Test
    @DisplayName("GET /api/v1/products returns a paginated page of products with no filter")
    void listReturnsPagedProducts() throws Exception {
        var aurelia = new Product(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Aurelia", null, List.of("rings"));
        var lume = new Product(UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Lume", "Pendant in 14k gold.", List.of("necklaces"));

        Page<ProductResponse> page = new PageImpl<>(
                List.of(ProductMapper.toResponse(aurelia), ProductMapper.toResponse(lume)),
                PageRequest.of(0, 20), 2);
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
        Page<ProductResponse> empty = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0);
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

        Page<ProductResponse> page = new PageImpl<>(List.of(ProductMapper.toResponse(aurelia)), PageRequest.of(0, 20), 1);
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
        Page<ProductResponse> empty = new PageImpl<>(Collections.emptyList(), PageRequest.of(2, 5), 12);
        when(service.findAll(isNull(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/api/v1/products").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(3));
    }
}
