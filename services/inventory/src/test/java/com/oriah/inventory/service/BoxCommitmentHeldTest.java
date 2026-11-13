package com.oriah.inventory.service;

import java.time.Instant;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.exception.InsufficientStockException;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.BoxCommitmentService.Commitment;
import com.oriah.inventory.service.BoxCommitmentService.Draw;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A product's set-aside is a PROMISE ({@code quantity}); while orders hold some of it, {@code held} says how much, and the box's
 * {@code committed} count is what is promised and NOT held: {@code committed = sum(quantity - held)}. The units an order holds sit in
 * the box's {@code reserved} count. These tests drive the record side of that, the way the reservation service does (lock the box row,
 * draw from the promise, move the same number from committed to reserved), and check that every older path that touches a promise
 * (setting it again, letting it go when the product is unpublished, a write-off cutting into it, the sale of a set-aside box) still
 * keeps the three numbers in step when part of the promise is held.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class BoxCommitmentHeldTest {

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private StockService stock;

    @Autowired
    private InventoryRepository inventories;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

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
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(7101));
    }

    private UUID product() {
        UUID product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        return product;
    }

    /** What the reservation service does for a box line: lock the box, draw from the promise, move that many from committed to reserved. */
    private Draw hold(UUID product, int qty) {
        return tx.execute(status -> {
            Inventory row = inventories.findByIdForUpdate(box).orElseThrow();
            Draw draw = commitments.drawForReservation(product, box, qty);
            if (draw.fromCommitted() > 0) {
                row.reserveFromCommitted(draw.fromCommitted());
            }
            if (qty > draw.fromCommitted()) {
                row.reserve(qty - draw.fromCommitted());
            }
            inventories.saveAndFlush(row);
            return draw;
        });
    }

    private Commitment record(UUID product) {
        return commitments.commitments(product, null).get(0);
    }

    private Inventory row() {
        return inventories.findById(box).orElseThrow();
    }

    /** committed = sum(quantity - held); count = free + committed + reserved; held never above the promise. */
    private void assertConsistent() {
        Inventory row = row();
        int unheld = jdbc.queryForObject("SELECT coalesce(sum(quantity - held), 0) FROM box_commitment WHERE box_variant_id = ?", Integer.class, box);
        assertThat(row.committed()).as("what is promised and not held equals the sum of the products' unheld promise").isEqualTo(unheld);
        assertThat(row.onHand()).as("count = free + set aside + reserved").isEqualTo(row.free() + row.committed() + row.reserved());
        assertThat(row.free()).isGreaterThanOrEqualTo(0);
        tx.executeWithoutResult(status -> commitments.verifyBox(box));
        assertThat(stock.reconcile(box).commitmentMismatches()).as("the reconciliation report agrees").isEmpty();
    }

    // ---------------------------------------------------------------- drawing from a promise

    @Test
    @DisplayName("an order takes boxes from the product's promise: the promise stays whole, and part of it is held")
    void drawingHoldsPartOfThePromise() {
        UUID gold = product();
        commitments.set(gold, 50, "user:kc-maya");

        Draw draw = hold(gold, 5);

        assertThat(draw.fromCommitted()).isEqualTo(5);
        assertThat(draw.commitmentId()).isEqualTo(record(gold).id());
        assertThat(record(gold).quantity()).as("the promise is whole").isEqualTo(50);
        assertThat(record(gold).held()).isEqualTo(5);
        assertThat(row().committed()).as("what is promised and not held").isEqualTo(45);
        assertThat(row().reserved()).isEqualTo(5);
        assertConsistent();
    }

    @Test
    @DisplayName("an order never takes more from a promise than is promised and not already held; the rest of its line comes from free")
    void drawingNeverTakesMoreThanTheUnheldPart() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");

        Draw first = hold(gold, 7);
        Draw second = hold(gold, 7);

        assertThat(first.fromCommitted()).isEqualTo(7);
        assertThat(second.fromCommitted()).as("only 3 were left unheld").isEqualTo(3);
        assertThat(second.commitmentId()).isEqualTo(first.commitmentId());
        assertThat(record(gold).held()).isEqualTo(10);
        assertThat(row().reserved()).as("7 + 7").isEqualTo(14);
        assertThat(row().committed()).isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("a product with no promise on this box draws nothing, and its whole line comes from free")
    void noPromiseDrawsNothing() {
        UUID silver = product();

        Draw draw = hold(silver, 4);

        assertThat(draw.fromCommitted()).isZero();
        assertThat(draw.commitmentId()).isNull();
        assertThat(row().reserved()).isEqualTo(4);
        assertThat(row().free()).isEqualTo(196);
        assertConsistent();
    }

    @Test
    @DisplayName("a hold that is let go puts its boxes back into the promise, once")
    void restoringReturnsTheBoxesToThePromise() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        Draw draw = hold(gold, 4);

        int back = tx.execute(status -> {
            Inventory row = inventories.findByIdForUpdate(box).orElseThrow();
            int restored = commitments.restoreHeld(draw.commitmentId(), 4);
            row.releaseToCommitted(restored);
            inventories.saveAndFlush(row);
            return restored;
        });

        assertThat(back).isEqualTo(4);
        assertThat(record(gold).held()).isZero();
        assertThat(record(gold).quantity()).isEqualTo(10);
        assertThat(row().committed()).isEqualTo(10);
        assertThat(row().reserved()).isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("a hold whose promise has been let go since goes back to free, not to a promise that is gone")
    void restoringAGonePromiseReturnsNothing() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        Draw draw = hold(gold, 4);
        commitments.release(gold, UUID.randomUUID(), "system:test");

        int back = tx.execute(status -> commitments.restoreHeld(draw.commitmentId(), 4));

        assertThat(back).as("the promise is gone").isZero();
    }

    @Test
    @DisplayName("a hold that is sold shrinks the promise and the hold together; a promise used up is closed")
    void consumingShrinksThePromise() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        Draw draw = hold(gold, 4);
        UUID action = UUID.randomUUID();

        int off = tx.execute(status -> commitments.consumeHeld(draw.commitmentId(), 4, action, ACTOR, Instant.parse("2026-11-12T10:00:00Z")));

        assertThat(off).isEqualTo(4);
        assertThat(record(gold).quantity()).isEqualTo(6);
        assertThat(record(gold).held()).isZero();
        List<String> history = jdbc.queryForList("SELECT kind || ':' || qty_delta || ':' || quantity_after FROM box_commitment_change WHERE product_id = ? ORDER BY id",
                String.class, gold);
        assertThat(history).containsExactly("ALLOCATE:10:10", "CONSUME:-4:6");

        Draw rest = hold(gold, 6);
        tx.execute(status -> commitments.consumeHeld(rest.commitmentId(), 6, UUID.randomUUID(), ACTOR, Instant.parse("2026-11-12T10:00:00Z")));
        assertThat(commitments.commitments(gold, null)).as("used up: the promise is closed").isEmpty();
    }

    @Test
    @DisplayName("selling a hold whose promise has been let go takes nothing off any promise")
    void consumingAGonePromiseTakesNothing() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        Draw draw = hold(gold, 4);
        commitments.release(gold, UUID.randomUUID(), "system:test");

        int off = tx.execute(status -> commitments.consumeHeld(draw.commitmentId(), 4, UUID.randomUUID(), ACTOR, Instant.parse("2026-11-12T10:00:00Z")));

        assertThat(off).isZero();
    }

    // ---------------------------------------------------------------- the older paths, with part of a promise held

    @Test
    @DisplayName("setting aside again cannot go below what orders hold: it is refused and says how many are held")
    void cannotPromiseLessThanIsHeld() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        hold(gold, 6);

        assertThatThrownBy(() -> commitments.set(gold, 5, "user:kc-maya"))
                .isInstanceOf(CommitmentProblem.class)
                .satisfies(e -> {
                    CommitmentProblem problem = (CommitmentProblem) e;
                    assertThat(problem.code()).isEqualTo("held-by-reservations");
                    assertThat(problem.properties()).containsEntry("held", 6);
                });
        assertThat(record(gold).quantity()).as("the refused change changed nothing").isEqualTo(10);
        assertConsistent();
    }

    @Test
    @DisplayName("setting aside again takes more from free or gives some back counting only the part that is not held")
    void replacingWhileHeld() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        hold(gold, 4);                                             // promise 10, held 4, promised-and-not-held 6

        commitments.set(gold, 12, "user:kc-maya");                 // 2 more from free
        assertThat(record(gold).quantity()).isEqualTo(12);
        assertThat(record(gold).held()).isEqualTo(4);
        assertThat(row().committed()).isEqualTo(8);
        assertConsistent();

        commitments.set(gold, 4, "user:kc-maya");                  // exactly what is held: nothing left unheld
        assertThat(record(gold).quantity()).isEqualTo(4);
        assertThat(row().committed()).isZero();
        assertThat(row().free()).isEqualTo(196);
        assertConsistent();
    }

    @Test
    @DisplayName("a product unpublished while orders hold part of its promise lets go only the part nobody holds, and the snapshot is applied")
    void unpublishingWhileHeld() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        Draw draw = hold(gold, 4);

        commitments.applyProduct(new ProductSnapshot(gold, "SELLABLE", "DRAFT", box, 1, false), UUID.randomUUID(), "system:test");

        assertThat(commitments.commitments(gold, null)).as("the promise is let go").isEmpty();
        assertThat(row().committed()).as("the unheld 6 went back to free").isZero();
        assertThat(row().reserved()).as("the order still holds its 4").isEqualTo(4);
        assertThat(row().free()).isEqualTo(196);
        assertConsistent();
        // The order's line, released later, finds no promise to go back to: its boxes go to free.
        int back = tx.execute(status -> commitments.restoreHeld(draw.commitmentId(), 4));
        assertThat(back).isZero();
    }

    @Test
    @DisplayName("a write-off that has to cut into promises cuts only what is not held, newest first")
    void writeOffCutsOnlyTheUnheldPart() {
        UUID silver = product();
        UUID gold = product();
        commitments.set(silver, 190, "user:kc-maya");
        commitments.set(gold, 10, "user:kc-maya");                 // newest
        hold(gold, 4);                                             // gold: promise 10, held 4, unheld 6; free is 0

        var result = stock.writeOff(UUID.randomUUID(), box, 6, StockReason.WRITE_OFF_BROKEN, null, "dropped", ACTOR).orElseThrow();

        assertThat(result.cutCommitments()).extracting(BoxCommitmentService.CutCommitment::productId, BoxCommitmentService.CutCommitment::cut)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(gold, 6));
        assertThat(record(gold).quantity()).as("10 - 6 cut").isEqualTo(4);
        assertThat(record(gold).held()).as("what the order holds is untouched").isEqualTo(4);
        assertConsistent();
    }

    @Test
    @DisplayName("selling set-aside boxes that no order holds cannot sell what an order is holding")
    void theSetAsideSaleCannotSellAHeldBox() {
        UUID gold = product();
        commitments.set(gold, 10, "user:kc-maya");
        hold(gold, 4);

        assertThatThrownBy(() -> stock.consumeCommitted(gold, box, 7, UUID.randomUUID(), null, ACTOR))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("6");
        assertThat(record(gold).quantity()).as("the refused sale changed nothing").isEqualTo(10);

        stock.consumeCommitted(gold, box, 6, UUID.randomUUID(), null, ACTOR);
        assertThat(record(gold).quantity()).isEqualTo(4);
        assertThat(record(gold).held()).isEqualTo(4);
        assertConsistent();
    }
}
