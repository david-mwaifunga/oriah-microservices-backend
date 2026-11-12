package com.oriah.procurement.controller;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a purchase order does to the supplier it names, and what a supplier's state does to new
 * orders, including when two requests meet. These are the Phase 2 hand-offs that waited for
 * orders to exist:
 *
 * <ul>
 *   <li>a supplier that any order names is never deleted: it is stopped instead, so history
 *       can still say who the goods came from;</li>
 *   <li>a supplier that has been stopped gets no new orders;</li>
 *   <li>both hold when the two requests are in flight at the same moment: one waits for the
 *       other and then sees what it did;</li>
 *   <li>an order is still readable after its supplier row is gone.</li>
 * </ul>
 *
 * <p>The races are driven deterministically, not by sleeping and hoping. A request is parked
 * inside the (mocked) catalog call, which happens AFTER it has locked the supplier, and the
 * other request is started while it is parked: if it could get past the supplier it would
 * finish before the first is released, and the test says so.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SupplierPurchaseOrderRulesTest {

    static final String PATH = "/api/v1/admin/procurement/purchase-orders";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";

    /** Long enough that a request which is really waiting on a lock is still waiting. */
    private static final long STILL_WAITING_MS = 750;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    private ExecutorService pool;

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    // ----------------------------------------------------------- fixtures

    private String supplier(String name) throws Exception {
        MvcResult created = mvc.perform(post(SUPPLIERS).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private static Entry ring(UUID id) {
        return new Entry(id, true, UUID.randomUUID(), "Aurelia Ring", "Aurelia Ring - Ring Size M", "RING-" + id,
                "SELLABLE", "DRAFT", true, false);
    }

    private void catalogKnows(UUID variant) {
        Map<UUID, Entry> byId = Map.of(variant, ring(variant));
        when(catalog.lookup(any())).thenAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream().map(byId::get).toList());
        });
    }

    private static String orderBody(String supplierId, UUID variant) {
        return "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variant
                + "\",\"quantity\":2,\"totalPaid\":100.00}]}";
    }

    private String placedOrder(String supplierId) throws Exception {
        UUID variant = UUID.randomUUID();
        catalogKnows(variant);
        MvcResult result = mvc.perform(post(PATH).with(SupplierCrudTest.maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(supplierId, variant)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private int deleteSupplier(String id) throws Exception {
        return mvc.perform(delete(SUPPLIERS + "/" + id).with(SupplierCrudTest.maya()))
                .andReturn().getResponse().getStatus();
    }

    private long liveSuppliers(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE id = ?::uuid AND is_deleted = false",
                Long.class, id);
    }

    // ------------------------------------------------ delete: orders keep a supplier

    @Test
    @DisplayName("a supplier that has an order cannot be deleted: 409 that says to stop it instead, and nothing changes")
    void aSupplierWithAnOrderIsNotDeleted() throws Exception {
        String supplier = supplier("Dubai Gold");
        placedOrder(supplier);

        mvc.perform(delete(SUPPLIERS + "/" + supplier).with(SupplierCrudTest.maya()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"))
                .andExpect(jsonPath("$.detail").value(containsString("Dubai Gold")))
                .andExpect(jsonPath("$.detail").value(containsString("purchase orders")))
                .andExpect(jsonPath("$.detail").value(containsString("Stop using it")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(liveSuppliers(supplier)).as("the supplier is still there").isEqualTo(1);
    }

    @Test
    @DisplayName("a cancelled order still counts: the supplier it names is kept, because history names it")
    void aCancelledOrderStillKeepsItsSupplier() throws Exception {
        String supplier = supplier("Cancelled Co");
        String order = placedOrder(supplier);
        mvc.perform(post(PATH + "/" + order + "/cancel").with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        assertThat(deleteSupplier(supplier)).isEqualTo(409);
        assertThat(liveSuppliers(supplier)).isEqualTo(1);
    }

    @Test
    @DisplayName("a supplier with no orders is deleted as before, and so is a stopped one")
    void aSupplierWithNoOrdersIsStillDeleted() throws Exception {
        String fresh = supplier("Mistake Ltd");
        String stopped = supplier("Stopped Ltd");
        mvc.perform(patch(SUPPLIERS + "/" + stopped).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());

        assertThat(deleteSupplier(fresh)).isEqualTo(204);
        assertThat(deleteSupplier(stopped)).isEqualTo(204);
        assertThat(liveSuppliers(fresh)).isZero();
        assertThat(liveSuppliers(stopped)).isZero();
    }

    @Test
    @DisplayName("an order is still read in full after its supplier's row has been deleted, naming it and saying it is deleted")
    void historyStillNamesADeletedSupplier() throws Exception {
        String supplier = supplier("Vanished Traders");
        String order = placedOrder(supplier);
        // Not reachable through the API any more (that is the point of the rule above), but a row
        // can still be marked deleted, by a mistake or a repair, and an order must not break.
        jdbc.update("UPDATE suppliers SET is_deleted = true WHERE id = ?::uuid", supplier);

        mvc.perform(get(PATH + "/" + order).with(SupplierCrudTest.maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier.id").value(supplier))
                .andExpect(jsonPath("$.supplier.name").value("Vanished Traders"))
                .andExpect(jsonPath("$.supplier.deleted").value(true));
        mvc.perform(get(PATH).param("supplierId", supplier).with(SupplierCrudTest.maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].supplier.name").value("Vanished Traders"));
    }

    @Test
    @DisplayName("a deleted supplier gets no new order: 404, as for any supplier that does not exist")
    void aDeletedSupplierGetsNoOrder() throws Exception {
        String supplier = supplier("Gone Ltd");
        assertThat(deleteSupplier(supplier)).isEqualTo(204);
        UUID variant = UUID.randomUUID();
        catalogKnows(variant);

        mvc.perform(post(PATH).with(SupplierCrudTest.maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(supplier, variant)))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------------- the races

    /**
     * Parks the first request that asks catalog about variants until the test lets it go, and says
     * when it has arrived. Everything the request did before asking catalog (taking the supplier)
     * has happened by the time {@code arrived} counts down.
     */
    private static final class ParkedCatalog {
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
    }

    private ParkedCatalog parkCatalogOn(UUID variant) {
        ParkedCatalog parked = new ParkedCatalog();
        Entry known = ring(variant);
        when(catalog.lookup(any())).thenAnswer(call -> {
            if (parked.calls.incrementAndGet() == 1) {
                parked.arrived.countDown();
                if (!parked.release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the parked catalog call");
                }
            }
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream().map(id -> known).toList());
        });
        return parked;
    }

    private Future<Integer> placeInBackground(String supplier, UUID variant) {
        return pool.submit(() -> mvc.perform(post(PATH).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(supplier, variant)))
                .andReturn().getResponse().getStatus());
    }

    private void assertStillWaiting(Future<?> request, String what) throws Exception {
        assertThatThrownBy(() -> request.get(STILL_WAITING_MS, TimeUnit.MILLISECONDS))
                .as(what)
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("a delete that arrives while an order is being placed waits for it, then finds the order and is refused with 409")
    void deleteWaitsForAnOrderBeingPlaced() throws Exception {
        String supplier = supplier("Racing Supplier");
        UUID variant = UUID.randomUUID();
        ParkedCatalog parked = parkCatalogOn(variant);

        Future<Integer> placing = placeInBackground(supplier, variant);
        assertThat(parked.arrived.await(30, TimeUnit.SECONDS)).as("the order reached catalog").isTrue();

        // The order is half-placed: it has the supplier locked and nothing saved yet.
        Future<Integer> deleting = pool.submit(() -> deleteSupplier(supplier));
        assertStillWaiting(deleting, "the delete must wait for the order being placed, not run past it");

        parked.release.countDown();

        assertThat(placing.get(30, TimeUnit.SECONDS)).as("the order was placed").isEqualTo(201);
        assertThat(deleting.get(30, TimeUnit.SECONDS)).as("the delete, once it ran, saw the order").isEqualTo(409);
        assertThat(liveSuppliers(supplier)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE supplier_id = ?::uuid",
                Long.class, supplier)).isEqualTo(1);
    }

    @Test
    @DisplayName("an order placed while the supplier is being stopped waits, then is refused: a stopped supplier gets no new orders")
    void placingWaitsForAStopInProgress() throws Exception {
        String supplier = supplier("Stopping Supplier");
        UUID variant = UUID.randomUUID();
        catalogKnows(variant);

        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate stopping = new TransactionTemplate(transactions);
        Future<?> stop = pool.submit(() -> stopping.executeWithoutResult(status -> {
            // The row is changed and locked; the change is not yet visible to anyone else.
            jdbc.update("UPDATE suppliers SET active = false, version = version + 1 WHERE id = ?::uuid", supplier);
            stopped.countDown();
            try {
                commit.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();

        Future<Integer> placing = placeInBackground(supplier, variant);
        assertStillWaiting(placing, "the order must wait for the stop, not read the supplier as still in use");

        commit.countDown();
        stop.get(30, TimeUnit.SECONDS);

        assertThat(placing.get(30, TimeUnit.SECONDS)).as("the order saw the supplier stopped").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE supplier_id = ?::uuid",
                Long.class, supplier)).isZero();
    }

    @Test
    @DisplayName("a supplier that is stopped is refused new orders with a plain 409 that names it")
    void aStoppedSupplierGetsNoOrder() throws Exception {
        String supplier = supplier("Fycargo");
        mvc.perform(patch(SUPPLIERS + "/" + supplier).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        UUID variant = UUID.randomUUID();
        catalogKnows(variant);

        mvc.perform(post(PATH).with(SupplierCrudTest.maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(supplier, variant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Fycargo")))
                .andExpect(jsonPath("$.detail").value(containsString("stopped")));
    }
}
