package com.oriah.procurement.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Supplier and courier invoices over HTTP: uploaded against a purchase order or a consignment,
 * listed, downloaded and voided.
 *
 * <p>A document is evidence. What the uploader CLAIMS the file is does not matter: the type is
 * read from the file's own first bytes and only a PDF, JPEG or PNG is kept. A file is never
 * rendered by the service or by a browser that is handed it: it is served as a download, marked
 * not to be sniffed, with a policy that lets it do nothing. It is never edited and never
 * deleted: a mistaken one is voided with a reason and stays on record.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class DocumentHttpTest {

    static final String CONSIGNMENTS = "/api/v1/admin/procurement/consignments";
    static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";
    static final String DOCUMENTS = "/api/v1/admin/procurement/documents";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    private final Map<UUID, Entry> known = new HashMap<>();

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
        known.clear();
        when(catalog.lookup(any())).thenAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream()
                    .map(id -> known.getOrDefault(id, new Entry(id, false, null, null, null, null, null, null, null, null)))
                    .toList());
        });
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    // ----------------------------------------------------------- fixtures

    private static byte[] pdf(String tag) {
        return ("%PDF-1.4\n" + tag + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(String tag) {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] body = tag.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    private static byte[] jpeg(String tag) {
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] body = tag.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** A one-line order, and (when asked) a draft consignment holding it. */
    private String order() throws Exception {
        UUID variant = UUID.randomUUID();
        known.put(variant, new Entry(variant, true, UUID.randomUUID(), "Ring", "Ring - M", "RING-" + variant.toString().substring(0, 8),
                "SELLABLE", "DRAFT", true, false));
        MvcResult supplier = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Docs Supplier " + UUID.randomUUID().toString().substring(0, 8) + "\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn();
        String supplierId = JsonPath.read(supplier.getResponse().getContentAsString(), "$.id");
        MvcResult result = mvc.perform(post(ORDERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variant
                                + "\",\"quantity\":2,\"totalPaid\":100.00}]}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private record Draft(String consignment, String order) {
    }

    private Draft consignment() throws Exception {
        String order = order();
        MvcResult version = mvc.perform(get(ORDERS + "/" + order).with(maya())).andReturn();
        long v = ((Number) JsonPath.read(version.getResponse().getContentAsString(), "$.version")).longValue();
        MvcResult created = mvc.perform(post(CONSIGNMENTS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseOrders\":[{\"id\":\"" + order + "\",\"version\":" + v + "}]}"))
                .andExpect(status().isCreated()).andReturn();
        return new Draft(JsonPath.read(created.getResponse().getContentAsString(), "$.id"), order);
    }

    private ResultActions upload(String path, String filename, byte[] bytes, String claimedType, String kind) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, claimedType, bytes);
        var request = multipart(path).file(file).with(maya());
        if (kind != null) {
            request = request.param("kind", kind);
        }
        return mvc.perform(request);
    }

    private ResultActions upload(String path, byte[] bytes, String kind) throws Exception {
        return upload(path, "invoice.pdf", bytes, "application/pdf", kind);
    }

    private String uploaded(String path, byte[] bytes, String kind) throws Exception {
        MvcResult result = upload(path, bytes, kind).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM procurement_document", Long.class);
    }

    // ----------------------------------------------------------- uploading

    @Test
    @DisplayName("a courier invoice is uploaded against a consignment: 201 with Location, its fingerprint, size and who uploaded it")
    void uploadToAConsignment() throws Exception {
        Draft draft = consignment();
        byte[] bytes = pdf("courier invoice 1");

        MvcResult result = mvc.perform(multipart(CONSIGNMENTS + "/" + draft.consignment() + "/documents")
                        .file(new MockMultipartFile("file", "Fycargo Invoice.pdf", "application/pdf", bytes))
                        .param("kind", "COURIER_INVOICE").param("note", "Bill of 12 Nov").with(maya()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerType").value("CONSIGNMENT"))
                .andExpect(jsonPath("$.ownerId").value(draft.consignment()))
                .andExpect(jsonPath("$.kind").value("COURIER_INVOICE"))
                .andExpect(jsonPath("$.filename").value("Fycargo Invoice.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.sha256").value(sha256(bytes)))
                .andExpect(jsonPath("$.note").value("Bill of 12 Nov"))
                .andExpect(jsonPath("$.uploadedBy").value("kc-maya"))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty())
                .andExpect(jsonPath("$.voided").value(false))
                .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result.getResponse().getHeader("Location")).as("the only thing to GET is the file")
                .isEqualTo(DOCUMENTS + "/" + id + "/content");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a supplier invoice is uploaded against a purchase order")
    void uploadToAnOrder() throws Exception {
        String order = order();

        upload(ORDERS + "/" + order + "/documents", pdf("supplier invoice"), "SUPPLIER_INVOICE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.ownerId").value(order))
                .andExpect(jsonPath("$.kind").value("SUPPLIER_INVOICE"));
    }

    @Test
    @DisplayName("the type is read from the file, not from what the uploader said: a PNG called .pdf is a PNG, and a program claiming to be a PDF is refused")
    void theTypeComesFromTheBytes() throws Exception {
        Draft draft = consignment();
        String path = CONSIGNMENTS + "/" + draft.consignment() + "/documents";

        upload(path, "scan.pdf", png("a photographed invoice"), "application/pdf", "COURIER_INVOICE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.filename").value("scan.pdf.png"));
        upload(path, "photo", jpeg("another"), "text/plain", "COURIER_INVOICE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.filename").value("photo.jpg"));
        upload(path, "invoice.pdf", "MZ\u0090\u0000 this is a program".getBytes(StandardCharsets.ISO_8859_1),
                "application/pdf", "COURIER_INVOICE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("PDF, JPEG or PNG")));
        upload(path, "notes.txt", "just some words".getBytes(StandardCharsets.UTF_8), "text/plain", "OTHER")
                .andExpect(status().isBadRequest());
        assertThat(count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the filename is tidied: the path an upload carried is dropped, and quotes and control characters are removed")
    void theFilenameIsTidied() throws Exception {
        Draft draft = consignment();
        String path = CONSIGNMENTS + "/" + draft.consignment() + "/documents";

        upload(path, "C:\\Users\\mwaif\\Desktop\\..\\invoice \"final\";x.pdf", pdf("one"), "application/pdf", "OTHER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("invoice final;x.pdf"));
        upload(path, "../../etc/passwd.pdf", pdf("two"), "application/pdf", "OTHER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("passwd.pdf"));
        upload(path, "x".repeat(300) + ".pdf", pdf("three"), "application/pdf", "OTHER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value(org.hamcrest.Matchers.hasLength(120)));
    }

    @Test
    @DisplayName("an empty file, a file over 5 MB, a missing file or kind, and an unknown kind are each a 400")
    void uploadValidation() throws Exception {
        Draft draft = consignment();
        String path = CONSIGNMENTS + "/" + draft.consignment() + "/documents";

        upload(path, new byte[0], "OTHER").andExpect(status().isBadRequest());
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(pdf("big"), 0, tooBig, 0, pdf("big").length);
        upload(path, tooBig, "OTHER").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("5 MB")));
        upload(path, pdf("no kind"), null).andExpect(status().isBadRequest());
        upload(path, pdf("bad kind"), "RECEIPT").andExpect(status().isBadRequest());
        mvc.perform(multipart(path).param("kind", "OTHER").with(maya())).andExpect(status().isBadRequest());
        assertThat(count()).isZero();

        byte[] exactly = new byte[5 * 1024 * 1024];
        System.arraycopy(pdf("exactly"), 0, exactly, 0, pdf("exactly").length);
        upload(path, exactly, "OTHER").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a courier invoice or a ZRA assessment belongs to a consignment, not to a purchase order")
    void kindsFitTheirOwners() throws Exception {
        String order = order();
        Draft draft = consignment();

        upload(ORDERS + "/" + order + "/documents", pdf("a"), "COURIER_INVOICE").andExpect(status().isBadRequest());
        upload(ORDERS + "/" + order + "/documents", pdf("b"), "ZRA_ASSESSMENT").andExpect(status().isBadRequest());
        upload(ORDERS + "/" + order + "/documents", pdf("c"), "OTHER").andExpect(status().isCreated());
        upload(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("d"), "ZRA_ASSESSMENT").andExpect(status().isCreated());
        upload(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("e"), "SUPPLIER_INVOICE").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an owner that does not exist is a 404")
    void unknownOwner() throws Exception {
        upload(CONSIGNMENTS + "/" + UUID.randomUUID() + "/documents", pdf("x"), "OTHER").andExpect(status().isNotFound());
        upload(ORDERS + "/" + UUID.randomUUID() + "/documents", pdf("x"), "OTHER").andExpect(status().isNotFound());
        mvc.perform(get(CONSIGNMENTS + "/" + UUID.randomUUID() + "/documents").with(maya())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the same file is not kept twice for one owner (409), but another owner may have it, and a voided copy may be uploaded again")
    void duplicates() throws Exception {
        Draft one = consignment();
        Draft two = consignment();
        String pathOne = CONSIGNMENTS + "/" + one.consignment() + "/documents";
        byte[] bytes = pdf("the same invoice");

        String first = uploaded(pathOne, bytes, "COURIER_INVOICE");
        upload(pathOne, bytes, "COURIER_INVOICE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already attached")));
        upload(CONSIGNMENTS + "/" + two.consignment() + "/documents", bytes, "COURIER_INVOICE").andExpect(status().isCreated());

        mvc.perform(post(DOCUMENTS + "/" + first + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"scanned upside down\"}")).andExpect(status().isOk());
        upload(pathOne, bytes, "COURIER_INVOICE").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an owner holds at most 10 documents in use: the 11th is a 409 that says to void one first")
    void atMostTen() throws Exception {
        Draft draft = consignment();
        String path = CONSIGNMENTS + "/" + draft.consignment() + "/documents";
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(uploaded(path, pdf("document " + i), "OTHER"));
        }

        upload(path, pdf("one too many"), "OTHER").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("void")));
        mvc.perform(post(DOCUMENTS + "/" + ids.get(0) + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"not needed\"}")).andExpect(status().isOk());
        upload(path, pdf("one too many"), "OTHER").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("uploads that arrive at the same moment cannot take an owner past 10 in use: exactly one more gets in")
    void theCapHoldsWhenUploadsRace() throws Exception {
        Draft draft = consignment();
        String path = CONSIGNMENTS + "/" + draft.consignment() + "/documents";
        for (int i = 0; i < 9; i++) {
            uploaded(path, pdf("already there " + i), "OTHER");
        }

        int racers = 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(racers);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(racers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                byte[] bytes = pdf("racing " + i);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return upload(path, bytes, "OTHER").andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (java.util.concurrent.Future<Integer> f : results) {
                statuses.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).as("one upload fits under the cap of 10, the others are refused")
                    .containsExactlyInAnyOrder(201, 409, 409, 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement_document WHERE voided_at IS NULL", Long.class))
                    .isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------- reading

    @Test
    @DisplayName("the list shows documents in use, newest first; voided ones only when asked, with who, when and why; and never the file itself")
    void listing() throws Exception {
        Draft draft = consignment();
        String path = CONSIGNMENTS + "/" + draft.consignment() + "/documents";
        String first = uploaded(path, pdf("first"), "COURIER_INVOICE");
        String second = uploaded(path, png("second"), "SUPPLIER_INVOICE");
        mvc.perform(post(DOCUMENTS + "/" + first + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"wrong bill\"}")).andExpect(status().isOk());

        mvc.perform(get(path).with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("content\""))));
        mvc.perform(get(path).param("includeVoided", "true").with(maya()))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[1].voided").value(true))
                .andExpect(jsonPath("$[1].voidReason").value("wrong bill"))
                .andExpect(jsonPath("$[1].voidedBy").value("kc-maya"))
                .andExpect(jsonPath("$[1].voidedAt").isNotEmpty());
    }

    @Test
    @DisplayName("a file is downloaded exactly as it was uploaded, as an attachment that cannot be sniffed, cached or made to do anything")
    void downloading() throws Exception {
        Draft draft = consignment();
        byte[] bytes = pdf("download me");
        String id = uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", bytes, "COURIER_INVOICE");

        MvcResult result = mvc.perform(get(DOCUMENTS + "/" + id + "/content").with(maya()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("invoice.pdf")))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(sha256(result.getResponse().getContentAsByteArray())).isEqualTo(sha256(bytes));
    }

    @Test
    @DisplayName("an image is downloaded with its own type, and an unknown document is a 404")
    void downloadingAnImage() throws Exception {
        Draft draft = consignment();
        byte[] bytes = png("an image");
        String id = uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", bytes, "OTHER");

        mvc.perform(get(DOCUMENTS + "/" + id + "/content").with(maya()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(bytes));
        mvc.perform(get(DOCUMENTS + "/" + UUID.randomUUID() + "/content").with(maya())).andExpect(status().isNotFound());
        mvc.perform(get(DOCUMENTS + "/not-a-uuid/content").with(maya())).andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------- voiding

    @Test
    @DisplayName("a mistaken document is voided with a reason, and stays on record and downloadable")
    void voiding() throws Exception {
        Draft draft = consignment();
        byte[] bytes = pdf("to be voided");
        String id = uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", bytes, "COURIER_INVOICE");

        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"This was last month's bill\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voided").value(true))
                .andExpect(jsonPath("$.voidReason").value("This was last month's bill"))
                .andExpect(jsonPath("$.voidedBy").value("kc-maya"));

        mvc.perform(get(DOCUMENTS + "/" + id + "/content").with(maya())).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        assertThat(count()).as("voiding deletes nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("voiding needs a reason (400), can happen once (409), and an unknown document is a 404")
    void voidingRules() throws Exception {
        Draft draft = consignment();
        String id = uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("rules"), "OTHER");

        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + "x".repeat(201) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"fine\"}")).andExpect(status().isOk());
        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"again\"}")).andExpect(status().isConflict());
        mvc.perform(post(DOCUMENTS + "/" + UUID.randomUUID() + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"fine\"}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a reason with the NUL character is a 400, as for every other text this service takes: the database cannot hold it")
    void aNulInTheReasonIsRefused() throws Exception {
        Draft draft = consignment();
        String id = uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("nul reason"), "OTHER");

        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"a\\u0000b\"}"))
                .andExpect(status().isBadRequest());
        // And the same for the note on an upload.
        mvc.perform(multipart(CONSIGNMENTS + "/" + draft.consignment() + "/documents")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf("nul note")))
                        .param("kind", "OTHER").param("note", "x\u0000y").with(maya()))
                .andExpect(status().isBadRequest());

        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"fine\"}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("there is no way to delete or replace a document through the API")
    void noDeleteNoReplace() throws Exception {
        Draft draft = consignment();
        String id = uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("keep"), "OTHER");

        mvc.perform(delete(DOCUMENTS + "/" + id).with(maya())).andExpect(status().is4xxClientError());
        mvc.perform(put(DOCUMENTS + "/" + id + "/content").with(maya()).content("x")).andExpect(status().is4xxClientError());
        assertThat(count()).isEqualTo(1);
    }

    // ----------------------------------------------- consignment life cycle

    @Test
    @DisplayName("a received consignment is given a late invoice without touching its costs: the version does not move")
    void aLateInvoiceOnAReceivedConsignment() throws Exception {
        Draft draft = consignment();
        MvcResult read = mvc.perform(get(CONSIGNMENTS + "/" + draft.consignment()).with(maya())).andReturn();
        long version = ((Number) JsonPath.read(read.getResponse().getContentAsString(), "$.version")).longValue();
        mvc.perform(post(CONSIGNMENTS + "/" + draft.consignment() + "/receive").with(maya())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}")).andExpect(status().isOk());

        upload(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("late"), "COURIER_INVOICE")
                .andExpect(status().isCreated());

        mvc.perform(get(CONSIGNMENTS + "/" + draft.consignment()).with(maya()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.version").value(version + 1));
    }

    @Test
    @DisplayName("deleting a draft consignment takes its documents with it, and leaves the documents of its purchase orders")
    void deletingADraftTakesItsDocuments() throws Exception {
        Draft draft = consignment();
        uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("on the consignment"), "COURIER_INVOICE");
        String onTheOrder = uploaded(ORDERS + "/" + draft.order() + "/documents", pdf("on the order"), "SUPPLIER_INVOICE");
        MvcResult read = mvc.perform(get(CONSIGNMENTS + "/" + draft.consignment()).with(maya())).andReturn();
        long version = ((Number) JsonPath.read(read.getResponse().getContentAsString(), "$.version")).longValue();

        mvc.perform(delete(CONSIGNMENTS + "/" + draft.consignment()).param("version", "" + version).with(maya()))
                .andExpect(status().isNoContent());

        assertThat(count()).isEqualTo(1);
        mvc.perform(get(DOCUMENTS + "/" + onTheOrder + "/content").with(maya())).andExpect(status().isOk());
    }

    // -------------------------------------------------------------- security

    @Test
    @DisplayName("only the procurement capability may touch documents: no token is a 401, another capability a 403")
    void security() throws Exception {
        Draft draft = consignment();
        String id = uploaded(CONSIGNMENTS + "/" + draft.consignment() + "/documents", pdf("secret"), "OTHER");
        RequestPostProcessor other = jwt().jwt(j -> j.subject("kc-other")).authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"));

        mvc.perform(get(DOCUMENTS + "/" + id + "/content")).andExpect(status().isUnauthorized());
        mvc.perform(get(DOCUMENTS + "/" + id + "/content").with(other)).andExpect(status().isForbidden());
        mvc.perform(get(CONSIGNMENTS + "/" + draft.consignment() + "/documents").with(other)).andExpect(status().isForbidden());
        mvc.perform(multipart(CONSIGNMENTS + "/" + draft.consignment() + "/documents")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf("x"))).param("kind", "OTHER").with(other))
                .andExpect(status().isForbidden());
        mvc.perform(post(DOCUMENTS + "/" + id + "/void").with(other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"no\"}")).andExpect(status().isForbidden());
    }
}
