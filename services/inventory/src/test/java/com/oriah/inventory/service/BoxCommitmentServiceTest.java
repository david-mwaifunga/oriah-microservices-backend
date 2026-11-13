package com.oriah.inventory.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService.Commitment;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boxes set aside for a product (CLIENT_DECISIONS 4.2). The rules, each with a test: setting aside needs free boxes and says how many
 * are free when it cannot; letting go moves them back to free and nothing leaves the building; a sale of a set-aside box lowers the
 * set-aside AND the count, or the promise would outlive the box; writing off boxes that free cannot absorb cuts into what is promised
 * and names the products. Throughout, the count of a box equals what is free plus what is set aside plus what is reserved, and what is
 * set aside equals the sum of the products' records.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class BoxCommitmentServiceTest {

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private StockService stock;

    @Autowired
    private InventoryRepository inventories;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID box;

    @BeforeEach
    void twoHundredBoxes() {
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        box = UUID.randomUUID();
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(7001));
    }

    /** A sellable product that inventory knows of, whose default box is {@code box}. */
    private UUID product() {
        return productWithBox(box);
    }

    private UUID productWithBox(UUID defaultBox) {
        UUID product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", defaultBox, 0, false), UUID.randomUUID(), "system:test");
        return product;
    }

    private void assertConsistent() {
        Inventory row = inventories.findById(box).orElseThrow();
        int sum = jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM box_commitment WHERE box_variant_id = ?", Integer.class, box);
        assertThat(row.committed()).as("what is set aside equals the sum of the products' records").isEqualTo(sum);
        assertThat(row.onHand()).as("count = free + set aside + reserved").isEqualTo(row.free() + row.committed() + row.reserved());
        assertThat(row.free()).isGreaterThanOrEqualTo(0);
        var report = stock.reconcile(box);
        assertThat(report.countMismatches()).isEmpty();
        assertThat(report.ledgerMismatches()).isEmpty();
        assertThat(report.commitmentMismatches()).isEmpty();
    }

    private Inventory row() {
        return inventories.findById(box).orElseThrow();
    }

    private List<String> history(UUID product) {
        return jdbc.queryForList("SELECT kind || ':' || qty_delta || ':' || quantity_after FROM box_commitment_change WHERE product_id = ? ORDER BY id",
                String.class, product);
    }

    // ---------------------------------------------------------------- setting aside

    @Test
    @DisplayName("setting aside raises what is set aside and lowers what is free, writes the record and its history, and leaves the count alone")
    void settingAside() {
        UUID gold = product();

        Commitment made = commitments.set(gold, 50, "user:kc-maya").commitment();

        assertThat(made.quantity()).isEqualTo(50);
        assertThat(made.boxVariantId()).isEqualTo(box);
        assertThat(row().committed()).isEqualTo(50);
        assertThat(row().free()).isEqualTo(150);
        assertThat(row().onHand()).isEqualTo(200);
        assertThat(history(gold)).containsExactly("ALLOCATE:50:50");
        assertConsistent();
    }

    @Test
    @DisplayName("50 pouches to the gold studs and 150 to the silver ones is fine out of 200; one more is refused, and says how many were free")
    void youCannotPromiseTheSameBoxTwice() {
        UUID gold = product();
        UUID silver = product();
        UUID bronze = product();
        commitments.set(gold, 50, "user:kc-maya");
        commitments.set(silver, 140, "user:kc-maya");

        assertThatThrownBy(() -> commitments.set(bronze, 11, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class)
                .satisfies(e -> {
                    CommitmentProblem problem = (CommitmentProblem) e;
                    assertThat(problem.code()).isEqualTo("insufficient-free-packaging");
                    assertThat(problem.properties()).containsEntry("available", 10);
                });
        assertThat(row().committed()).as("the refused promise changed nothing").isEqualTo(190);
        commitments.set(bronze, 10, "user:kc-maya");
        assertThat(row().free()).isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("boxes an unpaid order holds are not free to promise")
    void reservedBoxesCannotBePromised() {
        var inventory = row();
        inventory.reserve(180);
        inventories.saveAndFlush(inventory);

        assertThatThrownBy(() -> commitments.set(product(), 21, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class).satisfies(e -> assertThat(((CommitmentProblem) e).properties()).containsEntry("available", 20));
        commitments.set(product(), 20, "user:kc-maya");
        assertConsistent();
    }

    @Test
    @DisplayName("setting aside again for the same product replaces its record: more takes from free, less gives back, the same changes nothing")
    void replacing() {
        UUID gold = product();
        UUID silver = product();
        commitments.set(gold, 50, "user:kc-maya");
        commitments.set(silver, 140, "user:kc-maya");   // 10 free

        commitments.set(gold, 60, "user:kc-maya");       // needs 10 more: exactly what is free
        assertThat(row().committed()).isEqualTo(200);
        assertThatThrownBy(() -> commitments.set(gold, 61, "user:kc-maya")).isInstanceOf(CommitmentProblem.class);
        commitments.set(gold, 20, "user:kc-maya");       // gives 40 back
        assertThat(row().free()).isEqualTo(40);
        long before = jdbc.queryForObject("SELECT count(*) FROM box_commitment_change WHERE product_id = ?", Long.class, gold);
        commitments.set(gold, 20, "user:kc-maya");       // the same: nothing

        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_commitment_change WHERE product_id = ?", Long.class, gold)).isEqualTo(before);
        assertThat(history(gold)).containsExactly("ALLOCATE:50:50", "REPLACE:10:60", "REPLACE:-40:20");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_commitment WHERE product_id = ?", Long.class, gold)).as("still one record").isEqualTo(1);
        assertConsistent();
    }

    @Test
    @DisplayName("a quantity of zero or less is not a commitment (use release)")
    void quantityMustBePositive() {
        UUID gold = product();

        assertThatThrownBy(() -> commitments.set(gold, 0, "user:kc-maya")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commitments.set(gold, -3, "user:kc-maya")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the box is the product's default box, which inventory learned from catalog: no default box, or no word from catalog yet, is a 409")
    void theBoxComesFromTheProduct() {
        UUID noBox = productWithBox(null);
        assertThatThrownBy(() -> commitments.set(noBox, 5, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class).extracting("code").isEqualTo("no-default-box");

        assertThatThrownBy(() -> commitments.set(UUID.randomUUID(), 5, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class).extracting("code").isEqualTo("product-state-not-received");

        UUID packaging = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(packaging, "PACKAGING", "DRAFT", null, 0, false), UUID.randomUUID(), "system:test");
        assertThatThrownBy(() -> commitments.set(packaging, 5, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class).extracting("code").isEqualTo("not-a-sellable-product");

        UUID gone = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(gone, "SELLABLE", "ARCHIVED", box, 0, true), UUID.randomUUID(), "system:test");
        assertThatThrownBy(() -> commitments.set(gone, 5, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class).extracting("code").isEqualTo("not-a-sellable-product");
    }

    @Test
    @DisplayName("a box that was never received has nothing free: refused, with none available")
    void anUnreceivedBox() {
        UUID neverReceived = UUID.randomUUID();
        UUID gold = productWithBox(neverReceived);

        assertThatThrownBy(() -> commitments.set(gold, 1, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class).satisfies(e -> assertThat(((CommitmentProblem) e).properties()).containsEntry("available", 0));
    }

    // ---------------------------------------------------------------- letting go

    @Test
    @DisplayName("letting go moves the boxes from set-aside back to free, nothing leaves the building, and the record is closed into history")
    void releasing() {
        UUID gold = product();
        commitments.set(gold, 50, "user:kc-maya");

        Optional<Commitment> released = commitments.release(gold, null, "user:kc-maya");

        assertThat(released).isPresent();
        assertThat(released.get().quantity()).isEqualTo(50);
        assertThat(row().committed()).isZero();
        assertThat(row().free()).isEqualTo(200);
        assertThat(row().onHand()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_commitment WHERE product_id = ?", Long.class, gold)).isZero();
        assertThat(history(gold)).containsExactly("ALLOCATE:50:50", "RELEASE:-50:0");
        assertThat(commitments.release(gold, null, "user:kc-maya")).as("nothing left to let go").isEmpty();
        assertConsistent();
    }

    // ---------------------------------------------------------------- a sale

    @Test
    @DisplayName("a sale of a set-aside box lowers the set-aside AND the count and takes the oldest lot's cost; a repeat of the same sale line takes nothing")
    void aSaleOfASetAsideBox() {
        UUID gold = product();
        commitments.set(gold, 5, "user:kc-maya");
        UUID saleLine = UUID.randomUUID();

        stock.consumeCommitted(gold, box, 2, saleLine, null, ACTOR);

        assertThat(row().committed()).isEqualTo(3);
        assertThat(row().onHand()).isEqualTo(198);
        assertThat(row().free()).isEqualTo(195);
        assertThat(jdbc.queryForObject("SELECT quantity FROM box_commitment WHERE product_id = ?", Integer.class, gold)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sum(-qty_delta * unit_cost) FROM stock_movement WHERE action_id = ? AND reason = 'SALE'", java.math.BigDecimal.class, saleLine))
                .as("two boxes of the oldest lot, at K78.99").isEqualByComparingTo("157.98");
        assertThat(history(gold)).containsExactly("ALLOCATE:5:5", "CONSUME:-2:3");

        stock.consumeCommitted(gold, box, 2, saleLine, null, ACTOR);
        assertThat(row().onHand()).as("the same sale line again: nothing is taken twice").isEqualTo(198);
        assertThat(row().committed()).isEqualTo(3);
        assertConsistent();
    }

    @Test
    @DisplayName("when a sale uses the last set-aside box the record closes; a sale of more than is set aside is refused and changes nothing")
    void aSaleUsesTheLastOne() {
        UUID gold = product();
        commitments.set(gold, 2, "user:kc-maya");

        assertThatThrownBy(() -> stock.consumeCommitted(gold, box, 3, UUID.randomUUID(), null, ACTOR)).isInstanceOf(RuntimeException.class);
        assertThat(row().onHand()).isEqualTo(200);
        stock.consumeCommitted(gold, box, 2, UUID.randomUUID(), null, ACTOR);

        assertThat(row().committed()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_commitment WHERE product_id = ?", Long.class, gold)).isZero();
        assertThat(history(gold)).containsExactly("ALLOCATE:2:2", "CONSUME:-2:0");
        assertConsistent();
    }

    @Test
    @DisplayName("a sale from a set-aside that does not exist for that product is refused: a box promised to one product is not the next product's")
    void aSaleNeedsItsOwnProductsRecord() {
        UUID gold = product();
        UUID silver = product();
        commitments.set(gold, 5, "user:kc-maya");

        assertThatThrownBy(() -> stock.consumeCommitted(silver, box, 1, UUID.randomUUID(), null, ACTOR)).isInstanceOf(RuntimeException.class);
        assertThat(row().onHand()).isEqualTo(200);
        assertConsistent();
    }

    // ---------------------------------------------------------------- a write-off

    @Test
    @DisplayName("a write-off that free can absorb leaves every promise alone")
    void aWriteOffFreeCanAbsorb() {
        UUID gold = product();
        commitments.set(gold, 150, "user:kc-maya");   // 50 free

        var result = stock.writeOff(UUID.randomUUID(), box, 30, StockReason.WRITE_OFF_BROKEN, null, "crushed in the post", ACTOR).orElseThrow();

        assertThat(result.cutCommitments()).isEmpty();
        assertThat(row().committed()).isEqualTo(150);
        assertThat(row().onHand()).isEqualTo(170);
        assertConsistent();
    }

    @Test
    @DisplayName("a write-off free cannot absorb cuts into the promises, newest first, and the reply names the products that just lost their boxes")
    void aWriteOffCutsIntoPromisesNewestFirst() throws Exception {
        UUID gold = product();
        UUID silver = product();
        UUID bronze = product();
        commitments.set(gold, 60, "user:kc-maya");
        Thread.sleep(5);
        commitments.set(silver, 60, "user:kc-maya");
        Thread.sleep(5);
        commitments.set(bronze, 60, "user:kc-maya");      // 180 set aside, 20 free

        UUID action = UUID.randomUUID();
        var result = stock.writeOff(action, box, 110, StockReason.WRITE_OFF_BROKEN, null, "a crate was crushed", ACTOR).orElseThrow();

        // 20 from free, then 90 from the promises: bronze (newest) loses all 60, silver (next) loses 30; gold is untouched.
        assertThat(result.cutCommitments()).extracting("productId", "cut").containsExactly(
                org.assertj.core.groups.Tuple.tuple(bronze, 60), org.assertj.core.groups.Tuple.tuple(silver, 30));
        assertThat(row().onHand()).isEqualTo(90);
        assertThat(row().committed()).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT quantity FROM box_commitment WHERE product_id = ?", Integer.class, gold)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT quantity FROM box_commitment WHERE product_id = ?", Integer.class, silver)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM box_commitment WHERE product_id = ?", Long.class, bronze)).as("cut to nothing closes").isZero();
        assertThat(history(bronze)).containsExactly("ALLOCATE:60:60", "WRITE_OFF_CUT:-60:0");
        assertThat(history(silver)).containsExactly("ALLOCATE:60:60", "WRITE_OFF_CUT:-30:30");
        assertThat(jdbc.queryForObject("SELECT action_id FROM box_commitment_change WHERE product_id = ? AND kind = 'WRITE_OFF_CUT'", UUID.class, silver)).isEqualTo(action);
        assertConsistent();
    }

    @Test
    @DisplayName("a repeat of the same write-off request cuts nothing twice, and still names the same products")
    void aRepeatedWriteOffCutsNothingTwice() {
        UUID gold = product();
        commitments.set(gold, 190, "user:kc-maya");
        UUID request = UUID.randomUUID();
        stock.writeOff(request, box, 20, StockReason.WRITE_OFF_BROKEN, null, null, ACTOR);

        var again = stock.writeOff(request, box, 20, StockReason.WRITE_OFF_BROKEN, null, null, ACTOR).orElseThrow();

        assertThat(again.replay()).isTrue();
        assertThat(row().committed()).isEqualTo(180);
        assertThat(row().onHand()).isEqualTo(180);
        assertConsistent();
    }

    @Test
    @DisplayName("boxes that are written off and then taken back return to free: a promise that was cut is not remade")
    void aTakeBackDoesNotRemakeAPromise() {
        UUID gold = product();
        commitments.set(gold, 190, "user:kc-maya");
        UUID action = UUID.randomUUID();
        stock.writeOff(action, box, 20, StockReason.WRITE_OFF_BROKEN, null, null, ACTOR);
        assertThat(row().committed()).isEqualTo(180);

        stock.takeBack(UUID.randomUUID(), action, 20, null, null, ACTOR);

        assertThat(row().onHand()).isEqualTo(200);
        assertThat(row().committed()).as("the cut promise stays cut").isEqualTo(180);
        assertThat(row().free()).isEqualTo(20);
        assertConsistent();
    }

    // ---------------------------------------------------------------- the reconciliation

    @Test
    @DisplayName("the reconciliation says so when what is set aside is not what the records add up to")
    void reconciliationCatchesADrift() {
        UUID gold = product();
        commitments.set(gold, 50, "user:kc-maya");
        jdbc.update("UPDATE inventory SET committed = committed + 3 WHERE variant_id = ?", box);

        var report = stock.reconcile(box);

        assertThat(report.commitmentMismatches()).hasSize(1);
        assertThat(report.commitmentMismatches().get(0).variantId()).isEqualTo(box);
        assertThat(report.commitmentMismatches().get(0).committed()).isEqualTo(53);
        assertThat(report.commitmentMismatches().get(0).sumOfCommitments()).isEqualTo(50);
    }

    // ---------------------------------------------------------------- two at once

    @Test
    @DisplayName("two people setting aside the last boxes at once: exactly one is told yes, and the boxes are never promised twice")
    void twoAtOnce() throws Exception {
        UUID gold = product();
        UUID silver = product();
        commitments.set(product(), 80, "user:kc-maya");      // 120 free
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Boolean> goldWants = () -> attempt(go, gold, 100);
            Callable<Boolean> silverWants = () -> attempt(go, silver, 100);
            Future<Boolean> a = pool.submit(goldWants);
            Future<Boolean> b = pool.submit(silverWants);
            go.countDown();

            int yes = (a.get() ? 1 : 0) + (b.get() ? 1 : 0);

            assertThat(yes).as("only one of two promises of 100 fits in the 120 that were free").isEqualTo(1);
            assertThat(row().committed()).isEqualTo(180);
            assertConsistent();
        } finally {
            pool.shutdownNow();
        }
    }

    private Boolean attempt(CountDownLatch go, UUID product, int quantity) throws Exception {
        go.await();
        try {
            commitments.set(product, quantity, "user:kc-maya");
            return true;
        } catch (CommitmentProblem | org.springframework.dao.OptimisticLockingFailureException refused) {
            return false;
        }
    }

    // ---------------------------------------------------------------- everything, in any order

    @Test
    @DisplayName("through hundreds of random sets, releases, sales, write-offs and take-backs, the count and the records always agree")
    void invariantsHoldThroughRandomSequences() {
        Random random = new Random(20261113L);
        List<UUID> products = List.of(product(), product(), product());
        List<UUID> writeOffs = new java.util.ArrayList<>();
        java.util.Map<String, Integer> applied = new java.util.TreeMap<>();

        for (int step = 0; step < 300; step++) {
            UUID product = products.get(random.nextInt(products.size()));
            int qty = 1 + random.nextInt(40);
            try {
                switch (random.nextInt(6)) {
                    case 0, 1 -> {
                        commitments.set(product, qty, "user:kc-maya");
                        applied.merge("set", 1, Integer::sum);
                    }
                    case 2 -> {
                        if (commitments.release(product, null, "user:kc-maya").isPresent()) {
                            applied.merge("release", 1, Integer::sum);
                        }
                    }
                    case 3 -> {
                        stock.consumeCommitted(product, box, 1 + random.nextInt(5), UUID.randomUUID(), null, ACTOR);
                        applied.merge("consumeCommitted", 1, Integer::sum);
                    }
                    case 4 -> {
                        UUID action = UUID.randomUUID();
                        var result = stock.writeOff(action, box, qty, StockReason.WRITE_OFF_LOST, null, null, ACTOR).orElseThrow();
                        writeOffs.add(action);
                        applied.merge(result.cutCommitments().isEmpty() ? "writeOff" : "writeOffThatCut", 1, Integer::sum);
                    }
                    default -> {
                        if (!writeOffs.isEmpty()) {
                            stock.takeBack(UUID.randomUUID(), writeOffs.get(random.nextInt(writeOffs.size())), 1, null, null, ACTOR);
                            applied.merge("takeBack", 1, Integer::sum);
                        }
                    }
                }
            } catch (CommitmentProblem | com.oriah.inventory.exception.InsufficientStockException refused) {
                // Refused by a rule (not enough free, nothing set aside, nothing outstanding): nothing changed. Any OTHER failure (the
                // invariant checks throw IllegalStateException when the count and the records disagree) must fail the test, not be read as a refusal.
            }
            assertConsistent();
        }
        // The run must really have exercised every operation, or "nothing broke" proves little.
        assertThat(applied).containsKeys("set", "release", "consumeCommitted", "writeOff", "writeOffThatCut", "takeBack");
        assertThat(applied.values()).allSatisfy(n -> assertThat(n).isGreaterThanOrEqualTo(3));
    }
}
