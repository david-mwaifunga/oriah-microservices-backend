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
import org.springframework.http.HttpHeaders;
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
import com.oriah.catalog.storage.TestImages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for product image bytes.
 *
 * <p>Real Postgres and real HTTP through MockMvc; object storage is the
 * in-memory implementation, since the S3 one is proved separately against a
 * real server. The subject here is headers and status codes, which is exactly
 * what a service-level test cannot get at.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ProductImageHttpTest {

    /** Who the event log records as making these changes (ProductOutboxTest covers the actor). */
    private static final String STAFF = "user:catalog-test";

    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductService products;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository imageRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID liveProduct;
    private UUID draftProduct;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        imageRepository.deleteAll();
        productRepository.deleteAll();

        draftProduct = products.create("Unreleased Ring", null, List.of(), STAFF).id();

        liveProduct = products.create("Aurelia Solitaire Ring", null, List.of(), STAFF).id();
        PricedCatalogue.addPricedVariant(products, jdbc, liveProduct, "AUR-1", "4500.00", Map.of(), STAFF);
        products.publish(liveProduct, STAFF);
    }

    /** Uploads through the real admin endpoint and returns the new image id. */
    private String upload(UUID productId, byte[] bytes, String name) throws Exception {
        var file = new MockMultipartFile("file", name, "image/jpeg", bytes);
        String body = mvc.perform(multipart("/api/v1/admin/products/{id}/images", productId)
                        .file(file)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("uploading returns 201 with a relative URL the storefront can use directly")
    void uploadReturnsRelativeUrl() throws Exception {
        var file = new MockMultipartFile("file", "ring.jpg", "image/jpeg", TestImages.jpeg(600, 400));

        mvc.perform(multipart("/api/v1/admin/products/{id}/images", liveProduct)
                        .file(file)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                // Relative on purpose: the same string works in the browser
                // and during server-side rendering, and names no host that
                // would differ per environment or need persisting.
                .andExpect(jsonPath("$.url").value(
                        org.hamcrest.Matchers.startsWith("/api/v1/product-images/")))
                .andExpect(jsonPath("$.width").value(600))
                .andExpect(jsonPath("$.height").value(400));
    }

    @Test
    @DisplayName("a text file renamed .jpg is a 400, not a stored image")
    void uploadRejectsNonImage() throws Exception {
        var file = new MockMultipartFile(
                "file", "ring.jpg", "image/jpeg", TestImages.notAnImage());

        mvc.perform(multipart("/api/v1/admin/products/{id}/images", liveProduct)
                        .file(file)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isBadRequest());

        assertThat(imageRepository.count()).isZero();
    }

    @Test
    @DisplayName("uploading requires CATALOG_ADMIN")
    void uploadRequiresAdmin() throws Exception {
        var file = new MockMultipartFile("file", "ring.jpg", "image/jpeg", TestImages.jpeg(50, 50));

        mvc.perform(multipart("/api/v1/admin/products/{id}/images", liveProduct).file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a published product's image is served anonymously with immutable caching and a strong ETag")
    void publicGetServesBytesWithCachingHeaders() throws Exception {
        String imageId = upload(liveProduct, TestImages.jpeg(500, 500), "ring.jpg");

        mvc.perform(get("/api/v1/product-images/{id}", imageId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                // Immutable: the bytes behind an image id never change, so
                // there is nothing for a client to revalidate.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("immutable")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().exists(HttpHeaders.ETAG))
                // Set even though the content type is derived from decoding
                // the file: these bytes are served from the storefront's own
                // origin, so a browser guessing a type would make any future
                // ingest mistake exploitable rather than merely wrong.
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists(HttpHeaders.CONTENT_LENGTH));
    }

    @Test
    @DisplayName("a matching If-None-Match gets 304, and the 304 still carries the cache headers")
    void conditionalGetReturns304() throws Exception {
        String imageId = upload(liveProduct, TestImages.jpeg(300, 300), "ring.jpg");

        String etag = mvc.perform(get("/api/v1/product-images/{id}", imageId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotNull().startsWith("\"").endsWith("\"");

        mvc.perform(get("/api/v1/product-images/{id}", imageId)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                // A 304 that drops the caching directives makes a revalidating
                // client ask again on every single request, which is the
                // opposite of what the ETag was for.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("immutable")))
                .andExpect(header().string(HttpHeaders.ETAG, etag));
    }

    @Test
    @DisplayName("a comma-separated If-None-Match list containing our ETag also gets 304")
    void conditionalGetHandlesAList() throws Exception {
        String imageId = upload(liveProduct, TestImages.jpeg(300, 300), "ring.jpg");
        String etag = mvc.perform(get("/api/v1/product-images/{id}", imageId))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        // RFC 9110 permits a list, and clients send them. Comparing the raw
        // header against a single ETag -- the obvious implementation -- would
        // re-send the whole image every time, with no error to notice.
        mvc.perform(get("/api/v1/product-images/{id}", imageId)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"other\", " + etag))
                .andExpect(status().isNotModified());

        mvc.perform(get("/api/v1/product-images/{id}", imageId)
                        .header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("a non-matching If-None-Match gets the bytes")
    void staleEtagGetsTheBytes() throws Exception {
        String imageId = upload(liveProduct, TestImages.jpeg(300, 300), "ring.jpg");

        mvc.perform(get("/api/v1/product-images/{id}", imageId)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"stale\""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a Range header is ignored and the full image is returned")
    void rangeIsIgnored() throws Exception {
        String imageId = upload(liveProduct, TestImages.jpeg(300, 300), "ring.jpg");

        // Declared behaviour rather than an accident: whole images do not
        // benefit from ranges, and answering 206 with a partial body to a
        // client that asked speculatively would corrupt the image.
        mvc.perform(get("/api/v1/product-images/{id}", imageId)
                        .header(HttpHeaders.RANGE, "bytes=0-99"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unpublished product's image is 404 anonymously, and 404 not 403")
    void draftImageIsNotPublic() throws Exception {
        String imageId = upload(draftProduct, TestImages.jpeg(200, 200), "secret.jpg");

        // 403 would confirm the image exists, which is the one thing an
        // unreleased design must not confirm. Image ids are not secret -- they
        // come back from the admin API the operator is working in.
        mvc.perform(get("/api/v1/product-images/{id}", imageId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown image id is 404 with a problem body")
    void unknownImageIs404() throws Exception {
        mvc.perform(get("/api/v1/product-images/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.containsString("application/problem+json")));
    }

    @Test
    @DisplayName("the admin can preview a draft's image, uncacheably")
    void adminPreviewOfDraft() throws Exception {
        String imageId = upload(draftProduct, TestImages.jpeg(200, 200), "secret.jpg");

        mvc.perform(get("/api/v1/admin/products/{id}/images/{imageId}", draftProduct, imageId)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                // no-store, not the public path's year: this response is
                // specific to an authenticated operator, and a shared cache
                // keeping it would hand an unpublished product's image to
                // whoever asked next.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("the admin preview requires a token")
    void adminPreviewRequiresAuth() throws Exception {
        String imageId = upload(draftProduct, TestImages.jpeg(200, 200), "secret.jpg");

        mvc.perform(get("/api/v1/admin/products/{id}/images/{imageId}", draftProduct, imageId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("deleting an image is 204 and the public URL then 404s")
    void deleteThenGone() throws Exception {
        String imageId = upload(liveProduct, TestImages.jpeg(200, 200), "ring.jpg");
        mvc.perform(get("/api/v1/product-images/{id}", imageId)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/admin/products/{id}/images/{imageId}", liveProduct, imageId)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/product-images/{id}", imageId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deleting an image through the wrong product's path is 404")
    void deleteViaWrongProductIs404() throws Exception {
        String imageId = upload(draftProduct, TestImages.jpeg(200, 200), "secret.jpg");

        mvc.perform(delete("/api/v1/admin/products/{id}/images/{imageId}", liveProduct, imageId)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isNotFound());

        // Still there: the product id in the path is part of the identity, so
        // knowing an image id is not enough to delete it from any product.
        assertThat(imageRepository.findById(UUID.fromString(imageId))).isPresent();
    }

    @Test
    @DisplayName("an uploaded image appears in the product's detail payload")
    void imageAppearsOnTheProduct() throws Exception {
        String imageId = upload(liveProduct, TestImages.jpeg(400, 400), "ring.jpg");

        mvc.perform(get("/api/v1/products/{id}", liveProduct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.images[0].id").value(imageId))
                .andExpect(jsonPath("$.images[0].url")
                        .value("/api/v1/product-images/" + imageId));
    }

    @Test
    @DisplayName("re-uploading the same bytes does not add a second gallery entry")
    void reuploadIsIdempotentOverHttp() throws Exception {
        byte[] bytes = TestImages.jpeg(350, 350);
        String first = upload(liveProduct, bytes, "ring.jpg");
        String again = upload(liveProduct, bytes, "ring-copy.jpg");

        assertThat(again).isEqualTo(first);
        mvc.perform(get("/api/v1/products/{id}", liveProduct))
                .andExpect(jsonPath("$.images.length()").value(1));
    }
}
