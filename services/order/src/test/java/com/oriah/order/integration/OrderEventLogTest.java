package com.oriah.order.integration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventJson;
import com.oriah.events.OutboxRelay;
import com.oriah.order.QuoteFixtures;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.CustomerClient;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.events.OrderEvent;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every order change is written to order's event log -- the outbox -- in the
 * same transaction as the change, and the relay publishes the log on
 * {@code oriah.order} (MVP_PLAN Phase 1, item 2).
 *
 * <h2>The defect this closes</h2>
 *
 * Order used to send its Kafka message from inside the transaction, before
 * the order row was even written. A placement that then failed was announced
 * anyway: notification would email a customer about an order that does not
 * exist. And a crash after the commit but before the send lost the message
 * for good. Now the event is a row, committed with the order or rolled back
 * with it, and the relay publishes only what committed.
 *
 * <p>Two tests prove "the same transaction" by failing a transaction on
 * purpose with a deferred constraint trigger -- one that runs at COMMIT,
 * after the order is written and its event appended. The outbox hands out its
 * ids from a sequence that never rolls back, so "an id was handed out but no
 * row exists" is the proof that the event was appended inside that
 * transaction and went with it.
 *
 * <p>The outbox is append-only, so nothing here can empty it: each test reads
 * the rows of its own order, by its id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = "eureka.client.enabled=false")
class OrderEventLogTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ROLE_ORDER_ADMIN = new SimpleGrantedAuthority("ROLE_ORDER_ADMIN");
    private static final String ADDRESS = "11111111-1111-1111-1111-111111111111";
    private static final String CURRENCY = "ZMW";
    private static final String TOPIC = "oriah.order";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutputDestination output;

    @MockitoBean
    private CatalogPricingClient catalog;
    @MockitoBean
    private ShippingClient shipping;
    @MockitoBean
    private com.oriah.order.client.InventoryClient inventory;
    @MockitoBean
    private CustomerClient customer;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final Map<UUID, BigDecimal> catalogPrices = new HashMap<>();

    @BeforeEach
    void stubs() {
        // A shopper may hold three orders at once (Phase 7), and these tests all place as the same few shoppers. The outbox is append-only and
        // is not touched: each test reads the rows of its own order, by its id.
        orders.deleteAll();
        doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS)))).when(customer).myAddresses();
        com.oriah.order.SaleStubs.shippingSelfCollect(shipping);
        com.oriah.order.SaleStubs.inventoryHolds(inventory);
        doAnswer(inv -> {
            CatalogPricingClient.PricingRequest asked = inv.getArgument(0);
            return new CatalogPricingClient.PricingResponse(asked.variantIds().stream()
                    .map(id -> new CatalogPricingClient.Entry(id, "PRICED", UUID.randomUUID(),
                            "Aurelia 18k", "Aurelia 18k", catalogPrices.get(id), CURRENCY))
                    .toList());
        }).when(catalog).price(any());
    }

    // ------------------------------------------------------------ fixtures

    private static RequestPostProcessor customer(String subject) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(subject));
    }

    private static RequestPostProcessor staff(String subject) {
        return jwt().authorities(ROLE_ORDER_ADMIN).jwt(j -> j.subject(subject));
    }

    /** 2 x K100.00 + 1 x K50.00, self-collected: K250.00. */
    private ResultActions place(String subject) throws Exception {
        UUID ring = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        catalogPrices.put(ring, new BigDecimal("100.00"));
        catalogPrices.put(box, new BigDecimal("50.00"));
        String body = """
                {"shippingAddressId":"%s","deliveryOption":"SELF_COLLECT","currency":"%s","quoteDigest":"%s",
                 "items":[{"variantId":"%s","title":"Ring","quantity":2,"unitPrice":100.00},
                          {"variantId":"%s","title":"Box","quantity":1,"unitPrice":50.00}]}"""
                .formatted(ADDRESS, CURRENCY, QuoteFixtures.selfCollect(CURRENCY,
                        QuoteFixtures.line(ring, 2, "100.00"), QuoteFixtures.line(box, 1, "50.00")), ring, box);
        return mvc.perform(post("/api/v1/orders").with(customer(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID placeAs(String subject) throws Exception {
        String json = place(subject).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private void step(UUID orderId, String step, String packerSubject) throws Exception {
        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/dispatch/" + step)
                        .with(jwt().jwt(j -> j.subject(packerSubject)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void adminMoves(UUID orderId, String target, String staffSubject) throws Exception {
        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/transition").with(staff(staffSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"" + target + "\"}"))
                .andExpect(status().isOk());
    }

    /** One outbox row as stored. */
    record Row(long outboxId, UUID eventId, long seq, String eventType, int schemaVersion, String topic,
               Instant occurredAt, String actor, String payload) {
    }

    private List<Row> rows(UUID orderId) {
        return jdbc.query("""
                SELECT outbox_id, event_id, seq, event_type, schema_version, topic, occurred_at, actor,
                       payload::text AS payload
                FROM outbox WHERE aggregate_type = 'order' AND aggregate_id = ? ORDER BY seq""",
                (rs, n) -> new Row(rs.getLong("outbox_id"), rs.getObject("event_id", UUID.class),
                        rs.getLong("seq"), rs.getString("event_type"), rs.getInt("schema_version"),
                        rs.getString("topic"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("actor"), rs.getString("payload")),
                orderId.toString());
    }

    private long outboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);
    }

    /**
     * The last id the outbox's sequence handed out. A sequence never rolls
     * back, so an append inside a transaction that later fails still moves it.
     */
    private long outboxIdsHandedOut() {
        return jdbc.queryForObject("""
                SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('outbox', 'outbox_id')::regclass), 0)""",
                Long.class);
    }

    /** Runs the relay until it has published every row in the outbox. */
    private void relayCaughtUp() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            relay.relayBatch();
            Long last = jdbc.queryForObject(
                    "SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'", Long.class);
            Long newest = jdbc.queryForObject("SELECT coalesce(max(outbox_id), 0) FROM outbox", Long.class);
            if (last.equals(newest)) {
                return;
            }
            // The scheduled relay may hold the cursor this round; try again.
            Thread.sleep(50);
        }
        throw new AssertionError("the relay did not catch up with the outbox within 15 s");
    }

    /** Every message waiting on oriah.order, oldest first. */
    private List<Message<byte[]>> published() {
        List<Message<byte[]>> messages = new ArrayList<>();
        Message<byte[]> message;
        while ((message = output.receive(200, TOPIC)) != null) {
            messages.add(message);
        }
        return messages;
    }

    /** Waits for the relay to publish the first event of this order. */
    private Message<byte[]> firstPublishedFor(UUID orderId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message<byte[]> message = output.receive(500, TOPIC);
            if (message != null && orderId.toString().equals(header(message, "aggregate-id"))) {
                return message;
            }
        }
        throw new AssertionError("nothing was published on " + TOPIC + " for order " + orderId + " within 15 s");
    }

    private static String header(Message<?> message, String name) {
        Object value = message.getHeaders().get(name);
        return value == null ? null : value.toString();
    }

    private String body(Message<byte[]> message) {
        return new String(message.getPayload(), StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------- placement

    @Test
    @DisplayName("placing an order appends exactly one order.created: the whole order, by the customer, when it was placed")
    void placementAppendsOneCreated() throws Exception {
        UUID orderId = placeAs("kc-alice");

        List<Row> rows = rows(orderId);
        assertThat(rows).hasSize(1);
        Row created = rows.get(0);
        assertThat(created.seq()).isEqualTo(1);
        assertThat(created.eventType()).isEqualTo("order.created");
        assertThat(created.schemaVersion()).isEqualTo(1);
        assertThat(created.topic()).isEqualTo(TOPIC);
        assertThat(created.actor()).isEqualTo("user:kc-alice");

        Order order = orders.findById(orderId).orElseThrow();
        // When it happened -- the moment the order was placed, to the microsecond Postgres keeps.
        assertThat(created.occurredAt()).isCloseTo(order.placedAt(), within(1, ChronoUnit.MICROS));
        // Written in the placing transaction, it is the snapshot of the order as it was then committed.
        assertThat(created.payload()).isEqualTo(EventJson.write(OrderEvent.of(order, null)));
        assertThat(created.payload()).contains("\"previousStatus\":null,\"newStatus\":\"CREATED\","
                + "\"total\":250.00,\"currency\":\"ZMW\",\"subtotal\":250.00,\"shippingAmount\":0.00,"
                + "\"discountAmount\":0.00,\"deliveryOption\":\"SELF_COLLECT\",\"paymentIntentId\":null");
    }

    @Test
    @DisplayName("order.created is part of the transaction that makes the order HELD: a hold that fails at commit takes it along")
    void createdCommitsWithTheHold() throws Exception {
        // A placement is two transactions since Phase 7: the order is written PENDING (nobody told), inventory holds the pieces, and ONE
        // transaction then marks the order HELD and appends order.created. A failure at THAT commit must take the event along.
        jdbc.execute("""
                CREATE FUNCTION test_refuse_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test: this hold fails at commit';
                END;
                $$""");
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER orders_test_commit_fails AFTER UPDATE ON orders
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                WHEN (NEW.keycloak_id = 'kc-commit-fails' AND NEW.hold_state = 'HELD') EXECUTE FUNCTION test_refuse_commit()""");
        try {
            long idsBefore = outboxIdsHandedOut();
            long rowsBefore = outboxRows();

            place("kc-commit-fails").andExpect(status().is5xxServerError());

            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE keycloak_id = 'kc-commit-fails' AND hold_state = 'PENDING'",
                    Long.class)).as("the order stays PENDING: written, never announced, for the shopper's repeat to finish").isEqualTo(1);
            assertThat(outboxIdsHandedOut())
                    .as("order.created was appended inside the hold's transaction (it drew an outbox id)")
                    .isEqualTo(idsBefore + 1);
            assertThat(outboxRows())
                    .as("... and was rolled back with the HELD marking it describes")
                    .isEqualTo(rowsBefore);
        } finally {
            jdbc.execute("DROP TRIGGER orders_test_commit_fails ON orders");
            jdbc.execute("DROP FUNCTION test_refuse_commit()");
        }
    }

    @Test
    @DisplayName("a placement whose own write fails appends nothing and is never published")
    void aFailedPlacementIsNeverAnnounced() throws Exception {
        // Refused when the order row is written (at the flush), not at commit.
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT orders_test_flush_fails CHECK (keycloak_id <> 'kc-flush-fails')");
        try {
            long idsBefore = outboxIdsHandedOut();

            place("kc-flush-fails").andExpect(status().is5xxServerError());

            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE keycloak_id = 'kc-flush-fails'",
                    Long.class)).isZero();
            assertThat(outboxIdsHandedOut())
                    .as("the order's own write failed first, so nothing was even appended")
                    .isEqualTo(idsBefore);
            relayCaughtUp();
            assertThat(published())
                    .as("an order that does not exist must never be announced to the customer")
                    .noneMatch(message -> body(message).contains("\"keycloakId\":\"kc-flush-fails\""));
        } finally {
            jdbc.execute("ALTER TABLE orders DROP CONSTRAINT orders_test_flush_fails");
        }
    }

    // ------------------------------------------- every transition, and who

    @Test
    @DisplayName("every change appends its snapshot in order, naming who acted: the customer, or the staff member")
    void everyChangeNamesItsActor() throws Exception {
        UUID orderId = placeAs("kc-alice");
        // Only money makes an order PAID (the settlement saga, tested in SettlementReceiveTest, which also pins order.paid): here the sale is made
        // by hand so this test is about the changes AFTER it, and who made them.
        jdbc.update("UPDATE orders SET status = 'PAID' WHERE id = ?", orderId);
        // A pickup is packed and handed over (Phase 8): two dispatch steps by the packer, no SHIPPED. The courier walk, with its fare, is DispatchStepsTest's.
        step(orderId, "pack", "kc-dave");
        step(orderId, "deliver", "kc-dave");

        List<Row> rows = rows(orderId);
        assertThat(rows).extracting(Row::seq).containsExactly(1L, 2L, 3L);
        assertThat(rows).extracting(Row::eventType).containsExactly("order.created", "order.fulfilled", "order.delivered");
        assertThat(rows).extracting(Row::actor).containsExactly("user:kc-alice", "user:kc-dave", "user:kc-dave");
        assertThat(rows.get(2).payload()).contains("\"previousStatus\":\"FULFILLED\",\"newStatus\":\"DELIVERED\"");

        UUID cancelled = placeAs("kc-alice");
        mvc.perform(post("/api/v1/orders/" + cancelled + "/cancel").with(customer("kc-alice")))
                .andExpect(status().isOk());
        List<Row> cancelledRows = rows(cancelled);
        assertThat(cancelledRows).extracting(Row::eventType).containsExactly("order.created", "order.cancelled");
        assertThat(cancelledRows).extracting(Row::actor).containsExactly("user:kc-alice", "user:kc-alice");
    }

    @Test
    @DisplayName("a refused change appends nothing")
    void aRefusedChangeAppendsNothing() throws Exception {
        UUID orderId = placeAs("kc-alice");

        // CREATED cannot be shipped; and bob cannot cancel alice's order.
        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/transition").with(staff("kc-maya"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetStatus\":\"SHIPPED\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(customer("kc-bob")))
                .andExpect(status().isNotFound());

        assertThat(rows(orderId)).extracting(Row::eventType).containsExactly("order.created");
    }

    // ------------------------------------------------- two changes at once

    /**
     * Two changes to one order at the same moment (the shopper cancelling from
     * two tabs). Both read it as CREATED, and from CREATED the move is legal --
     * so without a version on the order, both would succeed and the log would
     * say it was cancelled TWICE. Exactly one may win; the other must be told
     * (409), and only the winner is in the log. (Money arriving at the same
     * moment is a different race, kept apart by the settlement's own marker:
     * SettlementSagaTest.)
     *
     * <p>To make "the same moment" certain rather than lucky, the test holds
     * the outbox table shut: no event can be appended, so neither change can
     * commit, until both are under way -- the first stopped at the outbox, the
     * second behind it on the order's row (Postgres shows both as waiting).
     * Then it lets go.
     */
    @Test
    @DisplayName("cancelling twice at the same moment: one wins, the other is 409, and only the winner is logged")
    void cancelAndCancelAtOnce() throws Exception {
        UUID orderId = placeAs("kc-alice");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> firstCancel;
        Future<Integer> secondCancel;
        try (Connection gate = dataSource.getConnection()) {
            gate.setAutoCommit(false);
            try (Statement lock = gate.createStatement()) {
                // Reads still pass (the relay keeps running); appends wait.
                lock.execute("LOCK TABLE outbox IN EXCLUSIVE MODE");
            }
            firstCancel = pool.submit(() -> mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                    .with(customer("kc-alice"))).andReturn().getResponse().getStatus());
            secondCancel = pool.submit(() -> mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                    .with(customer("kc-alice"))).andReturn().getResponse().getStatus());
            awaitWaiting(2);
            gate.rollback();
        } finally {
            pool.shutdown();
        }
        int first = firstCancel.get(30, TimeUnit.SECONDS);
        int second = secondCancel.get(30, TimeUnit.SECONDS);

        assertThat(List.of(first, second)).as("[cancel, cancel]").containsExactlyInAnyOrder(200, 409);
        assertThat(rows(orderId)).extracting(Row::eventType).containsExactly("order.created", "order.cancelled");
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    /** Waits until this many statements are blocked on a lock in the database. */
    private void awaitWaiting(int statements) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        long waiting = 0;
        while (System.currentTimeMillis() < deadline) {
            waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
            if (waiting == statements) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("expected " + statements + " statements waiting on a lock, saw " + waiting);
    }

    // ----------------------------------------------------------- the relay

    @Test
    @DisplayName("the relay publishes each event on oriah.order with its envelope, keyed by the order id")
    void theRelayPublishesWithTheEnvelope() throws Exception {
        UUID orderId = placeAs("kc-alice");
        Row created = rows(orderId).get(0);

        Message<byte[]> message = firstPublishedFor(orderId);

        assertThat(body(message)).as("the bytes published are the bytes stored").isEqualTo(created.payload());
        assertThat((byte[]) message.getHeaders().get("kafka_messageKey"))
                .as("keyed by the order, so all of one order's events share a partition and stay in order")
                .isEqualTo(orderId.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(String.valueOf(message.getHeaders().get("contentType"))).startsWith("application/json");
        assertThat(header(message, "event-id")).isEqualTo(created.eventId().toString());
        assertThat(header(message, "event-type")).isEqualTo("order.created");
        assertThat(header(message, "aggregate-type")).isEqualTo("order");
        assertThat(header(message, "aggregate-id")).isEqualTo(orderId.toString());
        assertThat(header(message, "seq")).isEqualTo("1");
        assertThat(header(message, "outbox-id")).isEqualTo(Long.toString(created.outboxId()));
        assertThat(header(message, "occurred-at")).isEqualTo(created.occurredAt().toString());
        assertThat(Instant.parse(header(message, "recorded-at"))).isNotNull();
        assertThat(header(message, "actor")).isEqualTo("user:kc-alice");
        assertThat(header(message, "schema-version")).isEqualTo("1");
        assertThat(header(message, "producer")).isEqualTo("order");
    }
}
