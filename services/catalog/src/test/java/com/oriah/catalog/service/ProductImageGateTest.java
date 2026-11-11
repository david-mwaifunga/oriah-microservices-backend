package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
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

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.dto.VariantCreateRequest;
import com.oriah.catalog.repository.ProductImageRepository;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.catalog.storage.IngestGate;
import com.oriah.catalog.storage.TestImages;
import com.oriah.observability.ResourceNotFoundException;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upload and the working-memory gate, wired together in the running service.
 *
 * <p>The budget is the smallest the service will start with (306 MiB, one biggest-possible
 * picture), so one held permit uses all of it and a second upload has to wait.
 *
 * <p>What these pin is where the waiting happens. An upload that waits for memory must not
 * hold a database connection while it does: the pool has ten, every storefront read needs
 * one, and a handful of admin uploads queueing for photograph memory must not be able to
 * starve the shop.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "catalog.images.ingest.working-memory-mib=306",
        "catalog.images.ingest.max-wait=30s"
})
class ProductImageGateTest {

    private static final String STAFF = "user:catalog-test";
    private static final long MAX_PIXELS = 40_000_000L;

    @Autowired
    private ProductImageService service;

    @Autowired
    private IngestGate gate;

    @Autowired
    private ProductService products;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository imageRepository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private UUID liveProduct;

    @BeforeEach
    void seed() {
        imageRepository.deleteAll();
        productRepository.deleteAll();
        liveProduct = products.create("Aurelia Solitaire Ring", null, List.of(), STAFF).id();
        products.addVariant(liveProduct, new VariantCreateRequest(
                "AUR-1", new BigDecimal("4500.00"), "ZMW", Map.of()), STAFF);
        products.publish(liveProduct, STAFF);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("an upload waiting for photograph memory holds no database connection")
    void waitingUploadHoldsNoConnection() throws Exception {
        var everything = gate.admit(MAX_PIXELS);
        try {
            Future<?> upload = pool.submit(() ->
                    service.upload(liveProduct, TestImages.jpeg(400, 300), "a.jpg", "image/jpeg", "waiting"));
            waitUntil(() -> gate.waiting() == 1);

            var hikari = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            // The outbox relay borrows a connection now and then, so look for one moment with
            // none in use. An upload that kept its transaction open while it waited would keep
            // at least one in use for the whole of this window.
            int fewest = Integer.MAX_VALUE;
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < until) {
                fewest = Math.min(fewest, hikari.getActiveConnections());
                Thread.sleep(5);
            }
            assertThat(fewest).as("connections in use while the upload waits for memory").isZero();
            assertThat(upload.isDone()).as("still waiting").isFalse();
        } finally {
            everything.close();
        }

        // and once the memory is free it goes through
        waitUntil(() -> gate.waiting() == 0);
        waitUntil(() -> imageRepository.count() == 1);
    }

    @Test
    @DisplayName("a product that does not exist is a 404 straight away, without waiting for photograph memory")
    void missingProductDoesNotQueue() {
        var everything = gate.admit(MAX_PIXELS);
        try {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> service.upload(
                    UUID.randomUUID(), TestImages.jpeg(100, 100), "a.jpg", "image/jpeg", null))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0)).isLessThan(5);
            assertThat(gate.waiting()).isZero();
        } finally {
            everything.close();
        }
    }

    @Test
    @DisplayName("a file that is not an image is a 400 straight away, without waiting for photograph memory")
    void badFileDoesNotQueue() {
        var everything = gate.admit(MAX_PIXELS);
        try {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> service.upload(
                    liveProduct, TestImages.notAnImage(), "a.jpg", "image/jpeg", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0)).isLessThan(5);
        } finally {
            everything.close();
        }
    }

    @Test
    @DisplayName("the memory is back after an upload")
    void memoryComesBack() {
        var response = service.upload(liveProduct, TestImages.jpeg(640, 480), "a.jpg", "image/jpeg", null);

        assertThat(response.width()).isEqualTo(640);
        assertThat(gate.availableMiB()).isEqualTo(306);
        assertThat(gate.waiting()).isZero();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached in 15 s");
            }
            Thread.sleep(10);
        }
    }
}
