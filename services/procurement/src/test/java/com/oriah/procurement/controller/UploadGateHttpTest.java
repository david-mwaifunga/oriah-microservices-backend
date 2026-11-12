package com.oriah.procurement.controller;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;
import com.oriah.procurement.service.UploadGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Only a few invoices are taken in at once. Measured live: sixteen 5 MB uploads at the same moment left half of
 * them a 500 (the service's heap is small and each upload is held in memory about three times over while it is
 * read, fingerprinted and written); with only a gate of four in a 640 MiB container, a second burst later got
 * the container killed for memory. So a gate lets
 * {@code oriah.procurement.uploads.max-concurrent} through; the rest wait up to {@code max-wait} and are then told
 * to try again: 503 with {@code Retry-After}, nothing stored.
 *
 * <p>This context allows ONE upload at a time and waits 400 ms, so the refusal can be driven deterministically:
 * the test holds the only permit itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "oriah.procurement.uploads.max-concurrent=1",
        "oriah.procurement.uploads.max-wait=400ms"
})
class UploadGateHttpTest {

    static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UploadGate gate;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    private String order() throws Exception {
        UUID variant = UUID.randomUUID();
        Map<UUID, Entry> known = new HashMap<>();
        known.put(variant, new Entry(variant, true, UUID.randomUUID(), "Ring", "Ring - M", "RING-GATE", "SELLABLE", "DRAFT",
                true, false));
        when(catalog.lookup(any())).thenReturn(new VariantLookupResult(java.util.List.of(known.get(variant))));
        MvcResult supplier = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Gate Supplier " + UUID.randomUUID().toString().substring(0, 8) + "\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn();
        String supplierId = JsonPath.read(supplier.getResponse().getContentAsString(), "$.id");
        MvcResult placed = mvc.perform(post(ORDERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variant
                                + "\",\"quantity\":1,\"totalPaid\":10.00}]}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(placed.getResponse().getContentAsString(), "$.id");
    }

    private static byte[] pdf(String tag) {
        return ("%PDF-1.4\n" + tag).getBytes(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.ResultActions upload(String order, byte[] bytes) throws Exception {
        return mvc.perform(multipart(ORDERS + "/" + order + "/documents")
                .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", bytes))
                .param("kind", "SUPPLIER_INVOICE").with(maya()));
    }

    private long documents() {
        return jdbc.queryForObject("SELECT count(*) FROM procurement_document", Long.class);
    }

    @Test
    @DisplayName("when every place is taken the upload is told to try again: 503 with Retry-After, and nothing is stored")
    void aBusyServerAsksYouToRetry() throws Exception {
        String order = order();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Another upload is in progress: it holds the only place.
            Future<?> other = pool.submit(() -> gate.run(() -> {
                holding.countDown();
                release.await(30, TimeUnit.SECONDS);
                return 0;
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            upload(order, pdf("turned away"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.detail").value(containsString("busy")));
            assertThat(documents()).isZero();

            release.countDown();
            other.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // And once there is room again, the same upload goes through.
        upload(order, pdf("turned away")).andExpect(status().isCreated());
        assertThat(documents()).isEqualTo(1);
    }

    @Test
    @DisplayName("an upload that is refused for being the wrong kind of file gives its place back")
    void aRefusedFileGivesThePlaceBack() throws Exception {
        String order = order();

        upload(order, "MZ not a pdf".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        upload(order, "also not".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        upload(order, pdf("fine")).andExpect(status().isCreated());
    }
}
