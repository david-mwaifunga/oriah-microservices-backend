package com.oriah.catalog.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.catalog.PricedCatalogue;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.repository.ProductImageRepository;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.catalog.service.ProductService;
import com.oriah.catalog.storage.ImageIngestBusyException;
import com.oriah.catalog.storage.IngestGate;
import com.oriah.catalog.storage.TestImages;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an admin sees when catalog has no memory to spare for another photograph: a 503
 * that says when to try again, in the same error shape as every other refusal -- never a
 * 500, and never a crashed service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ProductImageBusyHttpTest {

    private static final String STAFF = "user:catalog-test";
    private static final SimpleGrantedAuthority CATALOG_ADMIN = new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductService products;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository imageRepository;

    @MockitoBean
    private IngestGate gate;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID liveProduct;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        imageRepository.deleteAll();
        productRepository.deleteAll();
        liveProduct = products.create("Aurelia Solitaire Ring", null, List.of(), STAFF).id();
        PricedCatalogue.addPricedVariant(products, jdbc, liveProduct, "AUR-1", "4500.00", Map.of(), STAFF);
        products.publish(liveProduct, STAFF);
        when(gate.admit(anyLong())).thenThrow(new ImageIngestBusyException());
    }

    @Test
    @DisplayName("an upload the gate cannot take is 503 with Retry-After, in the standard problem shape")
    void busyIs503WithRetryAfter() throws Exception {
        var file = new MockMultipartFile("file", "ring.jpg", "image/jpeg", TestImages.jpeg(600, 400));

        mvc.perform(multipart("/api/v1/admin/products/{id}/images", liveProduct)
                        .file(file)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("busy")));
    }

    @Test
    @DisplayName("nothing is stored when the gate says no")
    void busyStoresNothing() throws Exception {
        var file = new MockMultipartFile("file", "ring.jpg", "image/jpeg", TestImages.jpeg(600, 400));

        mvc.perform(multipart("/api/v1/admin/products/{id}/images", liveProduct)
                .file(file)
                .with(jwt().authorities(CATALOG_ADMIN)));

        org.assertj.core.api.Assertions.assertThat(imageRepository.count()).isZero();
    }
}
