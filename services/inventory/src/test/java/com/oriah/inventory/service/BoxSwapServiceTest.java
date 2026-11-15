package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A box changed after the order was paid (Phase 7). One transaction, keyed by the amendment id the order system mints so a retry changes
 * nothing twice: the original box's sale is REVERSED (the box goes back to the lot it came out of, at the cost it left at) and the replacement
 * is SOLD (oldest lot first, from the product's set-aside if that is where it is promised), BOTH dated to the day of the sale and recorded now,
 * so a month's packaging cost moves by exactly the difference and the sale's date never moves. It is its own primitive: a write-off's take-back
 * still refuses a sale. The swap is announced with what each side cost.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class BoxSwapServiceTest {

    private static final Instant SOLD_ON = Instant.parse("2026-11-10T10:00:00Z");

    @Autowired
    private BoxSwapService swaps;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private StockService stock;

    @Autowired
    private InventoryRepository inventories;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID box;
    private UUID velvetBox;
    private UUID product;
    private Line sale;

    @BeforeEach
    void aPaidSale() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        box = UUID.randomUUID();
        velvetBox = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        stock.applyReceipt(box(velvetBox, "2026-11-09T09:00:00Z"), context(4));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        sale = new Line(UUID.randomUUID(), ring, product, 2, UUID.randomUUID(), null);
        UUID reservation = UUID.randomUUID();
        reservations.reserve(reservation, UUID.randomUUID(), List.of(sale), null, ACTOR);
        reservations.consume(reservation, SOLD_ON, ACTOR);
    }

    private Inventory row(UUID variant) {
        return inventories.findById(variant).orElseThrow();
    }

    private BoxSwapService.Swap swap(UUID amendment, UUID replacementBox, UUID replacementSaleLine) {
        return swaps.swap(amendment, UUID.randomUUID(), product, sale.boxLineId(), replacementSaleLine, replacementBox, "the customer asked for the velvet box", ACTOR);
    }

    private void assertConsistent() {
        var report = stock.reconcile(null);
        assertThat(report.countMismatches()).isEmpty();
        assertThat(report.ledgerMismatches()).isEmpty();
        assertThat(report.commitmentMismatches()).isEmpty();
        assertThat(report.reservedMismatches()).isEmpty();
    }

    private Instant occurredAt(UUID action) {
        return jdbc.queryForObject("SELECT occurred_at FROM stock_action WHERE id = ?", Timestamp.class, action).toInstant();
    }

    @Test
    @DisplayName("the original box goes back to its lot and the replacement is sold, one for one, dated to the day of the SALE")
    void swapping() {
        UUID amendment = UUID.randomUUID();
        UUID replacementLine = UUID.randomUUID();

        var done = swap(amendment, velvetBox, replacementLine);

        assertThat(done.replay()).isFalse();
        assertThat(done.quantity()).isEqualTo(2);
        assertThat(done.occurredAt()).as("the sale's own date, not the day of the swap").isEqualTo(SOLD_ON);
        assertThat(done.recordedAt()).isEqualTo(TestClockConfiguration.CLOCK.instant());
        assertThat(row(box).onHand()).as("the first box is back on the shelf").isEqualTo(200);
        assertThat(row(velvetBox).onHand()).as("two velvet boxes went with the sale instead").isEqualTo(198);
        assertThat(jdbc.queryForObject("SELECT sum(qty_remaining) FROM cost_lot WHERE variant_id = ? AND superseded_at IS NULL", Integer.class, box)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT kind || ':' || reason || ':' || variant_id FROM stock_action WHERE id = ?", String.class, replacementLine))
                .isEqualTo("SALE:SALE:" + velvetBox);
        Map<String, Object> reversal = jdbc.queryForMap("SELECT kind, reason, reverses_action_id, qty FROM stock_action WHERE reverses_action_id = ?", sale.boxLineId());
        assertThat(reversal).containsEntry("kind", "TAKE_BACK").containsEntry("reason", "BOX_SWAPPED").containsEntry("qty", 2);
        assertThat(occurredAt(replacementLine)).isEqualTo(SOLD_ON);
        UUID reversalId = (UUID) jdbc.queryForObject("SELECT id FROM stock_action WHERE reverses_action_id = ?", UUID.class, sale.boxLineId());
        assertThat(occurredAt(reversalId)).isEqualTo(SOLD_ON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_swap WHERE amendment_id = ?", Integer.class, amendment)).isEqualTo(1);
        assertConsistent();
    }

    @Test
    @DisplayName("a retry of the same swap changes nothing and answers the same swap; the same amendment id for something else is refused")
    void idempotentByAmendmentId() {
        UUID amendment = UUID.randomUUID();
        UUID replacementLine = UUID.randomUUID();
        var first = swap(amendment, velvetBox, replacementLine);
        UUID order = UUID.randomUUID();

        var again = swaps.swap(amendment, first.orderId(), product, sale.boxLineId(), replacementLine, velvetBox, "the customer asked for the velvet box", ACTOR);

        assertThat(again.replay()).isTrue();
        assertThat(again.recordedAt()).isEqualTo(first.recordedAt());
        assertThat(row(velvetBox).onHand()).as("sold once").isEqualTo(198);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE reverses_action_id = ?", Integer.class, sale.boxLineId())).isEqualTo(1);
        assertThatThrownBy(() -> swaps.swap(amendment, order, product, sale.boxLineId(), UUID.randomUUID(), velvetBox, "something else entirely now", ACTOR))
                .isInstanceOf(CommitmentProblem.class).satisfies(e -> assertThat(((CommitmentProblem) e).code()).isEqualTo("amendment-id-used"));
        assertConsistent();
    }

    @Test
    @DisplayName("a box that was sold from a product's set-aside can be replaced by one that is set aside for it: the replacement shrinks that promise")
    void replacementFromThePromise() {
        // The sale's box was the product's default (box); now the customer wants the velvet box, which the product has set aside.
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", velvetBox, 0, false), UUID.randomUUID(), "system:test");
        commitments.set(product, 5, "user:kc-maya");

        swap(UUID.randomUUID(), velvetBox, UUID.randomUUID());

        assertThat(commitments.commitments(product, null).get(0).quantity()).as("5 - 2").isEqualTo(3);
        assertThat(row(velvetBox).committed()).isEqualTo(3);
        assertThat(row(velvetBox).onHand()).isEqualTo(198);
        assertConsistent();
    }

    @Test
    @DisplayName("a sale's box can be swapped once: a second swap of the same box line is refused")
    void onlyOnce() {
        swap(UUID.randomUUID(), velvetBox, UUID.randomUUID());

        assertThatThrownBy(() -> swap(UUID.randomUUID(), velvetBox, UUID.randomUUID()))
                .satisfies(e -> assertThat(((CommitmentProblem) e).code()).isEqualTo("already-swapped"));
        assertThat(row(velvetBox).onHand()).isEqualTo(198);
        assertConsistent();
    }

    @Test
    @DisplayName("what cannot be swapped is refused by name: an unknown sale line, the same box, a piece instead of a box, boxes that are not there")
    void refusals() {
        assertThatThrownBy(() -> swaps.swap(UUID.randomUUID(), UUID.randomUUID(), product, UUID.randomUUID(), UUID.randomUUID(), velvetBox, "a perfectly good reason", ACTOR))
                .satisfies(e -> assertThat(((CommitmentProblem) e).code()).isEqualTo("sale-line-not-found"));
        assertThatThrownBy(() -> swap(UUID.randomUUID(), box, UUID.randomUUID()))
                .satisfies(e -> assertThat(((CommitmentProblem) e).code()).isEqualTo("same-box"));
        assertThatThrownBy(() -> swaps.swap(UUID.randomUUID(), UUID.randomUUID(), product, sale.saleLineId(), UUID.randomUUID(), velvetBox, "a perfectly good reason", ACTOR))
                .satisfies(e -> assertThat(((CommitmentProblem) e).code()).isEqualTo("not-a-box-sale"));
        assertThatThrownBy(() -> swap(UUID.randomUUID(), ring, UUID.randomUUID()))
                .satisfies(e -> assertThat(((CommitmentProblem) e).code()).isEqualTo("not-packaging"));
        assertThatThrownBy(() -> swaps.swap(UUID.randomUUID(), UUID.randomUUID(), product, sale.boxLineId(), UUID.randomUUID(), velvetBox, "short", ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(row(box).onHand()).as("nothing changed").isEqualTo(198);
        assertConsistent();
    }

    @Test
    @DisplayName("a replacement box that is not there in enough number is refused, and the original sale stands")
    void aShortReplacementChangesNothing() {
        tx(() -> {
            Inventory v = inventories.findByIdForUpdate(velvetBox).orElseThrow();
            v.reserve(199);
            inventories.saveAndFlush(v);
        });

        assertThatThrownBy(() -> swap(UUID.randomUUID(), velvetBox, UUID.randomUUID()))
                .satisfies(e -> {
                    CommitmentProblem p = (CommitmentProblem) e;
                    assertThat(p.code()).isEqualTo("insufficient-stock");
                });

        assertThat(row(box).onHand()).as("the original box is still sold").isEqualTo(198);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE reverses_action_id = ?", Integer.class, sale.boxLineId())).isZero();
    }

    @Test
    @DisplayName("the same swap asked twice at once, with one replacement box's worth in stock, is made once and answered twice: the second is a repeat, never 'short' (order would call that proof nothing moved)")
    void twoAtOnceAreOneSwap() throws Exception {
        tx(() -> {
            Inventory v = inventories.findByIdForUpdate(velvetBox).orElseThrow();
            v.reserve(198);                      // exactly the two this sale needs are free
            inventories.saveAndFlush(v);
        });
        UUID amendment = UUID.randomUUID();
        UUID replacementLine = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            // Someone holds both stock rows, so both attempts are past their first look ("not made yet") before either can take the stock.
            var holder = pool.submit(() -> txTemplate.executeWithoutResult(s -> {
                inventories.findAllForUpdate(new java.util.TreeSet<>(List.of(box, velvetBox)));
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var first = pool.submit(() -> swaps.swap(amendment, order, product, sale.boxLineId(), replacementLine, velvetBox, "the customer asked for the velvet box", ACTOR));
            var second = pool.submit(() -> swaps.swap(amendment, order, product, sale.boxLineId(), replacementLine, velvetBox, "the customer asked for the velvet box", ACTOR));
            Thread.sleep(2500);
            release.countDown();
            holder.get(30, java.util.concurrent.TimeUnit.SECONDS);

            var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(List.of(a.replay(), b.replay())).as("one made the swap, the other is told it was made").containsExactlyInAnyOrder(false, true);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_swap WHERE amendment_id = ?", Integer.class, amendment)).isEqualTo(1);
        assertThat(row(velvetBox).onHand()).as("sold once").isEqualTo(198);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE reverses_action_id = ?", Integer.class, sale.boxLineId())).as("reversed once").isEqualTo(1);
        // (no reconciliation here: the test's own hand-made reservation of 198 boxes has no reservation line behind it)
    }

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate txTemplate;

    private void tx(Runnable work) {
        txTemplate.executeWithoutResult(s -> work.run());
    }

    @Test
    @DisplayName("the swap is announced with what each side cost, dated to the sale, so the profit report moves by exactly the difference")
    void announced() throws Exception {
        UUID amendment = UUID.randomUUID();
        UUID replacementLine = UUID.randomUUID();
        var done = swap(amendment, velvetBox, replacementLine);

        var events = jdbc.queryForList("""
                SELECT event_type, aggregate_type, topic, occurred_at, payload::text AS payload FROM outbox
                 WHERE aggregate_type = 'box_swap' AND aggregate_id = ?""", amendment.toString());

        assertThat(events).hasSize(1);
        var e = events.get(0);
        assertThat(e.get("event_type")).isEqualTo("box.swapped");
        assertThat(e.get("topic")).isEqualTo("oriah.inventory");
        assertThat(((Timestamp) e.get("occurred_at")).toInstant()).isEqualTo(SOLD_ON);
        JsonNode body = json.readTree((String) e.get("payload"));
        assertThat(body.get("amendmentId").asText()).isEqualTo(amendment.toString());
        assertThat(body.get("orderId").asText()).isEqualTo(done.orderId().toString());
        assertThat(body.get("productId").asText()).isEqualTo(product.toString());
        assertThat(body.get("quantity").asInt()).isEqualTo(2);
        assertThat(body.get("currency").asText()).isEqualTo("ZMW");
        assertThat(body.get("original").get("variantId").asText()).isEqualTo(box.toString());
        assertThat(new BigDecimal(body.get("original").get("cost").asText())).as("2 x K78.99 handed back").isEqualByComparingTo("157.98");
        assertThat(body.get("replacement").get("variantId").asText()).isEqualTo(velvetBox.toString());
        assertThat(body.get("replacement").get("actionId").asText()).isEqualTo(replacementLine.toString());
        assertThat(new BigDecimal(body.get("replacement").get("cost").asText())).isEqualByComparingTo("157.98");
        assertThat(Duration.between(Instant.parse(body.get("recordedAt").asText()), TestClockConfiguration.CLOCK.instant())).isZero();
    }
}
