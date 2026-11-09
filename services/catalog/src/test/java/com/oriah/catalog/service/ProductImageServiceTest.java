package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.jdbc.core.JdbcTemplate;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.repository.ProductImageRepository;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.catalog.storage.InMemoryProductImageStorage;
import com.oriah.catalog.storage.ProductImageStorage;
import com.oriah.catalog.storage.TestImages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle of a product image: upload, serve, delete.
 *
 * <h2>Real Postgres, in-memory object storage</h2>
 *
 * The database half is real because the behaviours under test are persistence
 * behaviours — the {@code UNIQUE (product_id, content_hash)} constraint that
 * makes a re-upload idempotent, and the product STATUS that decides whether an
 * image is publicly readable. The storage half is the in-memory
 * implementation, because {@code S3ProductImageStorageIT} already proves the
 * S3 one against a real server and a container per test here would buy
 * nothing.
 *
 * <p>Deliberately not a Mockito mock of {@link ProductImageStorage}: a mock
 * returns empty from {@code open}, so every assertion that bytes come back
 * intact would be asserting against a stub.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
        // No storage endpoint, so ObjectStorageConfig selects the in-memory
        // implementation. That is the configuration under test here.
})
class ProductImageServiceTest {

    /** Who the event log records as making these changes (ProductOutboxTest covers the actor). */
    private static final String STAFF = "user:catalog-test";

    @Autowired
    private ProductImageService service;

    @Autowired
    private ProductService products;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository imageRepository;

    @Autowired
    private ProductImageStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID draftProduct;
    private UUID liveProduct;

    @BeforeEach
    void seed() {
        imageRepository.deleteAll();
        productRepository.deleteAll();
        ((InMemoryProductImageStorage) storage).clear();

        draftProduct = products.create("Unreleased Ring", null, List.of(), STAFF).id();

        // Seeded through the SERVICE, not the repository. @SpringBootTest is
        // not transactional, so a repository findById here hands back a
        // detached entity whose lazy variants collection throws
        // LazyInitializationException the moment addVariant touches it. The
        // service methods own their transaction, which is also how the
        // production admin endpoints do this.
        liveProduct = products.create("Aurelia Solitaire Ring", null, List.of(), STAFF).id();
        products.addVariant(liveProduct, new VariantCreateRequest(
                "AUR-1", new BigDecimal("4500.00"), "ZMW", Map.of()), STAFF);
        products.publish(liveProduct, STAFF);
    }

    @Test
    @DisplayName("an upload creates a row and stores the re-encoded bytes")
    void uploadStoresBytesAndRow() {
        var response = service.upload(
                liveProduct, TestImages.jpeg(800, 600), "ring.jpg", "image/jpeg", "Front view");

        assertThat(response.id()).isNotNull();
        assertThat(response.url()).isEqualTo("/api/v1/product-images/" + response.id());
        assertThat(response.altText()).isEqualTo("Front view");
        assertThat(response.width()).isEqualTo(800);
        assertThat(response.height()).isEqualTo(600);

        assertThat(imageRepository.count()).isEqualTo(1);
        assertThat(((InMemoryProductImageStorage) storage).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-uploading identical bytes returns the SAME image rather than a duplicate")
    void reuploadIsIdempotent() {
        byte[] bytes = TestImages.jpeg(400, 400);

        var first = service.upload(liveProduct, bytes, "a.jpg", "image/jpeg", "First");
        var again = service.upload(liveProduct, bytes, "different-name.jpg", "image/jpeg", "Second");

        // One row, one object, and the original id. An admin who clicks upload
        // twice has not created a second gallery entry, and the gallery does
        // not show the same photograph twice.
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(imageRepository.count()).isEqualTo(1);
        assertThat(((InMemoryProductImageStorage) storage).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same bytes on two products are two separate images")
    void sameBytesDifferentProducts() {
        byte[] bytes = TestImages.jpeg(300, 300);

        var onLive = service.upload(liveProduct, bytes, "a.jpg", "image/jpeg", null);
        var onDraft = service.upload(draftProduct, bytes, "a.jpg", "image/jpeg", null);

        // Scoped per product so deleting one product's images can never remove
        // bytes another product is still serving.
        assertThat(onDraft.id()).isNotEqualTo(onLive.id());
        assertThat(imageRepository.count()).isEqualTo(2);
        assertThat(((InMemoryProductImageStorage) storage).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("uploading to a product that does not exist is rejected")
    void uploadToUnknownProduct() {
        assertThatThrownBy(() -> service.upload(
                UUID.randomUUID(), TestImages.jpeg(100, 100), "a.jpg", "image/jpeg", null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a PUBLISHED product's image is publicly servable, with the stored hash as its ETag")
    void publicServeOfLiveProduct() throws Exception {
        var uploaded = service.upload(
                liveProduct, TestImages.jpeg(500, 500), "a.jpg", "image/jpeg", null);

        var served = service.servePublicly(uploaded.id()).orElseThrow();

        assertThat(served.contentType()).isEqualTo("image/jpeg");
        assertThat(served.byteSize()).isPositive();
        assertThat(served.contentHash()).hasSize(64);
        try (var in = served.content()) {
            byte[] bytes = in.readAllBytes();
            assertThat(bytes).hasSize((int) served.byteSize());
            // The ETag must describe what the client actually receives, which
            // is why the hash is taken of the STORED bytes after re-encoding
            // rather than of the upload.
            assertThat(com.oriah.catalog.storage.ProductImageIngest.sha256Hex(bytes))
                    .isEqualTo(served.contentHash());
        }
    }

    @Test
    @DisplayName("a DRAFT product's image is NOT publicly servable")
    void publicServeOfDraftIsRefused() {
        var uploaded = service.upload(
                draftProduct, TestImages.jpeg(200, 200), "a.jpg", "image/jpeg", null);

        // Hiding the product from the list and the detail endpoint is not
        // enough if its photographs remain readable: an unreleased design
        // would leak through the image URL, which appears in the admin screen
        // the operator is working in.
        assertThat(service.servePublicly(uploaded.id())).isEmpty();
    }

    @Test
    @DisplayName("an ARCHIVED product's image is NOT publicly servable")
    void publicServeOfArchivedIsRefused() {
        var uploaded = service.upload(
                liveProduct, TestImages.jpeg(200, 200), "a.jpg", "image/jpeg", null);

        // Straight to SQL: there is no admin archive endpoint yet, and the
        // subject here is the serve path's status check rather than how the
        // status got there.
        jdbc.update("UPDATE products SET status = 'ARCHIVED' WHERE id = ?", liveProduct);

        assertThat(service.servePublicly(uploaded.id())).isEmpty();
    }

    @Test
    @DisplayName("the admin CAN serve a draft's image, which is how an operator reviews one")
    void adminServeOfDraftIsAllowed() {
        var uploaded = service.upload(
                draftProduct, TestImages.jpeg(200, 200), "a.jpg", "image/jpeg", null);

        assertThat(service.serveForAdmin(uploaded.id())).isPresent();
    }

    @Test
    @DisplayName("an unknown image id is empty on both paths")
    void unknownImageIsEmpty() {
        assertThat(service.servePublicly(UUID.randomUUID())).isEmpty();
        assertThat(service.serveForAdmin(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("deleting an image removes both the row and the stored object")
    void deleteRemovesRowAndObject() {
        var uploaded = service.upload(
                liveProduct, TestImages.jpeg(200, 200), "a.jpg", "image/jpeg", null);
        assertThat(((InMemoryProductImageStorage) storage).size()).isEqualTo(1);

        assertThat(service.delete(liveProduct, uploaded.id())).isTrue();

        // Both halves. A row without its object serves a 404 from a gallery
        // that still lists the image; an object without its row is storage
        // nobody will ever reclaim.
        assertThat(imageRepository.findById(uploaded.id())).isEmpty();
        assertThat(((InMemoryProductImageStorage) storage).size()).isZero();
    }

    @Test
    @DisplayName("deleting an image that belongs to a different product is refused")
    void cannotDeleteAnotherProductsImage() {
        var onDraft = service.upload(
                draftProduct, TestImages.jpeg(200, 200), "a.jpg", "image/jpeg", null);

        // The product id in the path is part of the identity, not decoration.
        // Without this check, knowing an image id would be enough to delete it
        // from any product.
        assertThat(service.delete(liveProduct, onDraft.id())).isFalse();
        assertThat(imageRepository.findById(onDraft.id())).isPresent();
    }

    @Test
    @DisplayName("deleting an unknown image is false, not an error")
    void deleteUnknownIsFalse() {
        assertThat(service.delete(liveProduct, UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("deleting the PRODUCT removes its images' objects, not just their rows")
    void deletingProductReclaimsStorage() {
        service.upload(liveProduct, TestImages.jpeg(200, 200), "a.jpg", "image/jpeg", null);
        service.upload(liveProduct, TestImages.jpeg(300, 300), "b.jpg", "image/jpeg", null);
        assertThat(((InMemoryProductImageStorage) storage).size()).isEqualTo(2);

        products.deleteById(liveProduct, STAFF);

        // The OBJECTS are reclaimed; the ROWS survive. Both halves are
        // deliberate and they changed together.
        //
        // Deleting a product is a soft delete, because the product_variants
        // row it would otherwise destroy carries an id that is a primary key
        // in inventory and search and the line identity in cart and order.
        // The product_images rows survive with it, so an order's history can
        // still describe what was bought.
        //
        // The objects do not survive, and nothing cascades them: object
        // storage has no foreign keys. Without the explicit sweep, every
        // deleted product would leave its photographs in storage forever --
        // and unlike the rows, nobody will ever serve those bytes again, so
        // keeping them buys nothing.
        assertThat(((InMemoryProductImageStorage) storage).size())
                .as("object storage is swept explicitly; nothing cascades into it")
                .isZero();
        assertThat(imageRepository.count())
                .as("image rows survive the soft delete, alongside the product")
                .isEqualTo(2);

        // And the product is unreachable, which is what "deleted" means here.
        assertThat(products.findByIdWithAvailability(liveProduct)).isEmpty();
        assertThat(products.findByIdForAdmin(liveProduct)).isEmpty();
    }

    @Test
    @DisplayName("a rejected upload leaves nothing behind in either the database or storage")
    void rejectedUploadLeavesNoTrace() {
        assertThatThrownBy(() -> service.upload(
                liveProduct, TestImages.notAnImage(), "fake.jpg", "image/jpeg", null))
                .isInstanceOf(IllegalArgumentException.class);

        // Validation happens before anything is written, so there is no
        // half-created state to clean up.
        assertThat(imageRepository.count()).isZero();
        assertThat(((InMemoryProductImageStorage) storage).size()).isZero();
    }

    @Test
    @DisplayName("images come back in upload order, so a gallery has a stable first image")
    void imagesAreOrdered() {
        var first = service.upload(liveProduct, TestImages.jpeg(100, 100), "a.jpg", "image/jpeg", null);
        var second = service.upload(liveProduct, TestImages.jpeg(200, 200), "b.jpg", "image/jpeg", null);
        var third = service.upload(liveProduct, TestImages.jpeg(300, 300), "c.jpg", "image/jpeg", null);

        var detail = products.findByIdWithAvailability(liveProduct).orElseThrow();

        // The first image is the card image on the product grid, so an
        // unstable order means the grid changes appearance between requests.
        assertThat(detail.images()).extracting("id")
                .containsExactly(first.id(), second.id(), third.id());
    }
}
