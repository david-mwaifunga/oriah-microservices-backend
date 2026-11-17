package com.oriah.inventory.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.inventory.ledger.FifoConformer.Action;
import com.oriah.inventory.ledger.FifoConformer.Chunk;
import com.oriah.inventory.ledger.FifoConformer.InsufficientLotsException;
import com.oriah.inventory.ledger.FifoConformer.Kind;
import com.oriah.inventory.ledger.FifoConformer.LiveChunk;
import com.oriah.inventory.ledger.FifoConformer.Lot;
import com.oriah.inventory.ledger.FifoConformer.Outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIFO cost flow is a pure function of two things: the variant's lots in FIFO order, and its actions (drains and
 * restores) in the order they were recorded. The conformer works out what the ledger SHOULD say, and what has to be
 * re-cut to make it say so. Whatever changes the lots (a late older receipt, a corrected cost) then needs no
 * special case: the ledger is made to equal the function's output.
 *
 * <p>No database here: the engine takes plain values. {@link Model} is a small in-memory ledger that applies an
 * outcome the way the database layer will, so the properties can be checked after any sequence of events.
 */
class FifoConformerTest {

    private static final UUID A = id(1), B = id(2), X = id(3), N1 = id(4), N2 = id(5), O1 = id(6);

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static Lot lot(UUID id, int qty) {
        return new Lot(id, qty);
    }

    private static Chunk chunk(UUID lot, int qty) {
        return new Chunk(lot, qty);
    }

    private static Action drain(int n, long seq, int qty) {
        return new Action(id(100 + n), seq, Kind.DRAIN, qty, null);
    }

    private static Action restore(int n, long seq, int qty, int ofDrain) {
        return new Action(id(100 + n), seq, Kind.RESTORE, qty, id(100 + ofDrain));
    }

    /** One returned piece put back but HELD: in its lot, not for sale until it is released or leaves. */
    private static Action held(int n, long seq, int ofDrain) {
        return Action.held(id(100 + n), seq, id(100 + ofDrain));
    }

    private static Action release(int n, long seq, int ofHeld) {
        return Action.release(id(100 + n), seq, id(100 + ofHeld));
    }

    private static Action redraw(int n, long seq, int ofHeld) {
        return Action.redraw(id(100 + n), seq, id(100 + ofHeld));
    }

    private static LiveChunk live(long movement, int action, UUID lot, int qty) {
        return new LiveChunk(movement, id(100 + action), lot, qty);
    }

    @Test
    @DisplayName("no actions: nothing to re-cut and every lot is whole")
    void nothingHappenedYet() {
        Outcome out = FifoConformer.conform(List.of(lot(A, 5), lot(B, 3)), List.of(), List.of());

        assertThat(out.recuts()).isEmpty();
        assertThat(out.remainingByLot()).containsEntry(A, 5).containsEntry(B, 3);
    }

    @Test
    @DisplayName("a drain takes the oldest lot first, and a ledger that already says so is left alone")
    void aDrainFromTheOldestLot() {
        Outcome out = FifoConformer.conform(List.of(lot(A, 5), lot(B, 5)), List.of(drain(1, 1, 3)),
                List.of(live(10, 1, A, 3)));

        assertThat(out.recuts()).isEmpty();
        assertThat(out.remainingByLot()).containsEntry(A, 2).containsEntry(B, 5);
    }

    @Test
    @DisplayName("a drain bigger than the oldest lot spills into the next, one chunk per lot")
    void aDrainSpansTwoLots() {
        Outcome out = FifoConformer.conform(List.of(lot(A, 5), lot(B, 5)), List.of(drain(1, 1, 7)),
                List.of(live(10, 1, A, 5), live(11, 1, B, 2)));

        assertThat(out.recuts()).isEmpty();
        assertThat(out.desired().get(id(101))).containsExactly(chunk(A, 5), chunk(B, 2));
        assertThat(out.remainingByLot()).containsEntry(A, 0).containsEntry(B, 3);
    }

    @Test
    @DisplayName("a take-back returns units to the newest-drained chunk first, at the lot they came out of")
    void aTakeBackGoesToTheNewestChunk() {
        Outcome out = FifoConformer.conform(List.of(lot(A, 5), lot(B, 5)),
                List.of(drain(1, 1, 7), restore(2, 2, 3, 1)),
                List.of(live(10, 1, A, 5), live(11, 1, B, 2), live(12, 2, B, 2), live(13, 2, A, 1)));

        assertThat(out.recuts()).isEmpty();
        assertThat(out.desired().get(id(102))).containsExactly(chunk(B, 2), chunk(A, 1));
        assertThat(out.remainingByLot()).containsEntry(A, 1).containsEntry(B, 5);
    }

    @Test
    @DisplayName("an OLDER lot that arrives after newer ones were sold from re-cuts the sale to take it first")
    void aLateOlderLotReflowsTheSale() {
        // The sale of 7 was recorded when only A and B existed: 5 from A, 2 from B. Then X (older) arrives.
        Outcome out = FifoConformer.conform(List.of(lot(X, 3), lot(A, 5), lot(B, 5)), List.of(drain(1, 1, 7)),
                List.of(live(10, 1, A, 5), live(11, 1, B, 2)));

        assertThat(out.recuts()).hasSize(1);
        FifoConformer.Recut recut = out.recuts().get(0);
        assertThat(recut.actionId()).isEqualTo(id(101));
        assertThat(recut.reversedMovementIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThat(recut.replacements()).containsExactly(chunk(X, 3), chunk(A, 4));
        assertThat(out.remainingByLot()).containsEntry(X, 0).containsEntry(A, 1).containsEntry(B, 5);
    }

    @Test
    @DisplayName("a take-back is re-cut whenever the drain it restores is, even if its own chunks look the same")
    void aDependentRestoreIsRecutWithItsDrain() {
        // Drain 7 was [A5, B2]; a take-back of 1 went to B. An older X1 arrives: the drain becomes [X1, A5, B1],
        // and the take-back still says B1, but it must now point at the NEW B chunk.
        Outcome out = FifoConformer.conform(List.of(lot(X, 1), lot(A, 5), lot(B, 5)),
                List.of(drain(1, 1, 7), restore(2, 2, 1, 1)),
                List.of(live(10, 1, A, 5), live(11, 1, B, 2), live(12, 2, B, 1)));

        assertThat(out.recuts()).extracting(FifoConformer.Recut::actionId).containsExactlyInAnyOrder(id(101), id(102));
        FifoConformer.Recut restoreRecut = out.recuts().stream().filter(r -> r.actionId().equals(id(102))).findFirst().orElseThrow();
        assertThat(restoreRecut.reversedMovementIds()).containsExactly(12L);
        assertThat(restoreRecut.replacements()).containsExactly(chunk(B, 1));
        assertThat(out.remainingByLot()).containsEntry(X, 0).containsEntry(A, 0).containsEntry(B, 5);
    }

    @Test
    @DisplayName("a corrected cost swaps the lots: what was sold and written off is laid over the new lots, FIFO")
    void anAmendmentSwapsTheLots() {
        // 7 rings. 3 sold in March and 1 written off in April came out of the old lot O1. The corrected receipt is
        // two new lots (5 and 2); the old lot is staged out, so it is simply not passed in.
        Outcome out = FifoConformer.conform(List.of(lot(N1, 5), lot(N2, 2)),
                List.of(drain(1, 1, 3), drain(2, 2, 1)),
                List.of(live(10, 1, O1, 3), live(11, 2, O1, 1)));

        assertThat(out.recuts()).hasSize(2);
        assertThat(out.recuts().get(0).replacements()).containsExactly(chunk(N1, 3));
        assertThat(out.recuts().get(1).replacements()).containsExactly(chunk(N1, 1));
        assertThat(out.remainingByLot()).containsEntry(N1, 1).containsEntry(N2, 2);
    }

    @Test
    @DisplayName("a drain that no lot can cover is refused, not shortened")
    void aDrainBeyondTheSupply() {
        assertThatThrownBy(() -> FifoConformer.conform(List.of(lot(A, 2)), List.of(drain(1, 1, 3)), List.of()))
                .isInstanceOf(InsufficientLotsException.class);
    }

    @Test
    @DisplayName("a take-back of more than is outstanding is refused")
    void aTakeBackBeyondWhatWasTaken() {
        assertThatThrownBy(() -> FifoConformer.conform(List.of(lot(A, 5)),
                List.of(drain(1, 1, 2), restore(2, 2, 3, 1)), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("actions are replayed in the order they were recorded, whatever order they are handed over in")
    void recordingOrderNotListOrder() {
        Outcome out = FifoConformer.conform(List.of(lot(A, 2), lot(B, 4)),
                List.of(drain(2, 2, 2), drain(1, 1, 2)), List.of());

        assertThat(out.desired().get(id(101))).containsExactly(chunk(A, 2));
        assertThat(out.desired().get(id(102))).containsExactly(chunk(B, 2));
    }

    // ----- properties, over a small in-memory ledger -----

    // ---- returned pieces: held in their own lot until somebody has looked at them (Phase 12) ------------------------------------------

    @Test
    @DisplayName("a returned piece goes back to the newest lot its sale drained and is HELD there: no later sale can take it")
    void aHeldRestoreIsNotDrained() {
        List<Lot> lots = List.of(lot(A, 1), lot(B, 3));
        List<Action> history = List.of(drain(1, 1, 2), held(2, 2, 1), drain(3, 3, 2));

        Outcome out = FifoConformer.conform(lots, history, List.of());

        assertThat(out.desired().get(id(102))).as("back to B, the newest lot the sale drained").containsExactly(chunk(B, 1));
        assertThat(out.desired().get(id(103))).as("the next sale takes B's two free units, never the held one").containsExactly(chunk(B, 2));
        assertThat(out.remainingByLot()).as("the held piece is still physical stock of its lot").containsEntry(A, 0).containsEntry(B, 1);
        assertThatThrownBy(() -> FifoConformer.conform(lots, List.of(drain(1, 1, 2), held(2, 2, 1), drain(3, 3, 2), drain(4, 4, 1)), List.of()))
                .as("only the held piece is left: it cannot be sold").isInstanceOf(InsufficientLotsException.class);
    }

    @Test
    @DisplayName("once released, the piece is ordinary stock of its lot again; the release itself moves nothing")
    void aReleasedUnitIsDrainedAgain() {
        Outcome out = FifoConformer.conform(List.of(lot(A, 1), lot(B, 3)),
                List.of(drain(1, 1, 2), held(2, 2, 1), release(3, 3, 2), drain(4, 4, 3)), List.of());

        assertThat(out.desired().get(id(103))).as("a release writes no row").isEmpty();
        assertThat(out.desired().get(id(104))).containsExactly(chunk(B, 3));
        assertThat(out.remainingByLot()).containsEntry(B, 0);
    }

    @Test
    @DisplayName("a held piece that leaves (written off, or moved to a marked-down listing) leaves the lot it is in, at that lot's cost")
    void aRedrawLeavesTheLotItsUnitIsIn() {
        Outcome out = FifoConformer.conform(List.of(lot(A, 1), lot(B, 1)),
                List.of(drain(1, 1, 2), held(2, 2, 1), held(3, 3, 1), redraw(4, 4, 3)), List.of());

        assertThat(out.desired().get(id(102))).containsExactly(chunk(B, 1));
        assertThat(out.desired().get(id(103))).as("the second piece back goes to the older lot").containsExactly(chunk(A, 1));
        assertThat(out.desired().get(id(104))).as("and leaves from that same lot").containsExactly(chunk(A, 1));
        assertThat(out.remainingByLot()).containsEntry(A, 0).containsEntry(B, 1);
    }

    @Test
    @DisplayName("the case a plain count cannot get right: two pieces of a two-lot sale come back, a later sale is recorded, and each piece still leaves from its own lot")
    void twoReturnedUnitsOfATwoLotSaleEachKeepTheirOwnLot() {
        // The sale took the last ring of the older lot A and one of B. Both came back (B first, then A), then four more were sold.
        // Without holds the later sale would take A's returned ring first (A is the oldest), and A's piece would later "leave" from B.
        Outcome out = FifoConformer.conform(List.of(lot(A, 1), lot(B, 5)),
                List.of(drain(1, 1, 2), held(2, 2, 1), held(3, 3, 1), drain(4, 4, 4), redraw(5, 5, 3), redraw(6, 6, 2)), List.of());

        assertThat(out.desired().get(id(104))).as("the later sale takes only B's free units").containsExactly(chunk(B, 4));
        assertThat(out.desired().get(id(105))).as("A's piece leaves from A").containsExactly(chunk(A, 1));
        assertThat(out.desired().get(id(106))).as("B's piece leaves from B").containsExactly(chunk(B, 1));
        assertThat(out.remainingByLot()).containsEntry(A, 0).containsEntry(B, 0);
    }

    @Test
    @DisplayName("when the sale is re-cut (an older lot arrived late), the returned piece and its exit follow the sale to the lot it now drains")
    void holdsFollowARecutOfTheirSale() {
        // As the ledger stood: the sale, the piece back and its exit all on B. Then the older lot A arrives.
        Outcome out = FifoConformer.conform(List.of(lot(A, 1), lot(B, 2)),
                List.of(drain(1, 1, 1), held(2, 2, 1), redraw(3, 3, 2)),
                List.of(live(10, 1, B, 1), live(11, 2, B, 1), live(12, 3, B, 1)));

        assertThat(out.recuts()).extracting(FifoConformer.Recut::actionId).containsExactly(id(101), id(102), id(103));
        assertThat(out.recuts()).allSatisfy(recut -> assertThat(recut.replacements()).containsExactly(chunk(A, 1)));
        assertThat(out.remainingByLot()).containsEntry(A, 0).containsEntry(B, 2);
    }

    @Test
    @DisplayName("an outcome decides a piece that is held, once: releasing or removing anything else, or twice, or before it came back, is refused")
    void releaseOrRedrawOfAnUnheldUnitIsRefused() {
        List<Lot> lots = List.of(lot(A, 3));
        assertThatThrownBy(() -> FifoConformer.conform(lots, List.of(drain(1, 1, 1), restore(2, 2, 1, 1), release(3, 3, 2)), List.of()))
                .as("an ordinary take-back is not held").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FifoConformer.conform(lots, List.of(drain(1, 1, 1), held(2, 2, 1), redraw(3, 3, 2), redraw(4, 4, 2)), List.of()))
                .as("it left once").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FifoConformer.conform(lots, List.of(drain(1, 1, 1), held(2, 2, 1), release(3, 3, 2), redraw(4, 4, 2)), List.of()))
                .as("released, then removed").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FifoConformer.conform(lots, List.of(drain(1, 1, 1), release(3, 2, 2), held(2, 3, 1)), List.of()))
                .as("decided before it came back").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FifoConformer.conform(lots,
                List.of(drain(1, 1, 2), new Action(id(102), 2, Kind.RESTORE_HELD, 2, id(101), null)), List.of()))
                .as("a held piece is one piece").isInstanceOf(IllegalArgumentException.class);
    }

    /** The ledger as the database layer will hold it: lots, actions in recording order, and each action's live chunks. */
    private static final class Model {
        final List<Lot> lots = new ArrayList<>();
        final List<Action> actions = new ArrayList<>();
        final Map<UUID, List<LiveChunk>> liveByAction = new LinkedHashMap<>();
        long nextMovement = 1;
        long nextSeq = 1;
        int nextAction = 1;

        List<LiveChunk> allLive() {
            return liveByAction.values().stream().flatMap(List::stream).toList();
        }

        Outcome conform() {
            return FifoConformer.conform(lots, actions, allLive());
        }

        /** Applies an outcome as the database layer will: reverse the live rows of each re-cut action, write the replacements. */
        void apply(Outcome out) {
            for (FifoConformer.Recut recut : out.recuts()) {
                List<LiveChunk> fresh = new ArrayList<>();
                for (Chunk c : recut.replacements()) {
                    fresh.add(new LiveChunk(nextMovement++, recut.actionId(), c.lotId(), c.qty()));
                }
                liveByAction.put(recut.actionId(), fresh);
            }
        }

        UUID record(Kind kind, int qty, UUID restores) {
            return record(kind, qty, restores, null);
        }

        UUID record(Kind kind, int qty, UUID restores, UUID assesses) {
            UUID actionId = new UUID(1, nextAction++);
            actions.add(new Action(actionId, nextSeq++, kind, qty, restores, assesses));
            liveByAction.put(actionId, new ArrayList<>());
            apply(conform());
            return actionId;
        }
    }

    @Test
    @DisplayName("after ANY mix of receipts, sales, take-backs, returned pieces (held, released, removed) and corrections: lots add up, and conforming twice changes nothing")
    void propertyOverRandomHistories() {
        for (long seed = 1; seed <= 400; seed++) {
            Random random = new Random(seed);
            Model model = new Model();
            Map<UUID, Integer> outstanding = new HashMap<>();   // drain action -> units a take-back could still return
            List<UUID> drains = new ArrayList<>();
            List<UUID> heldPieces = new ArrayList<>();          // returned pieces nobody has decided yet
            int lotNo = 1;
            for (int step = 0; step < 40; step++) {
                int roll = random.nextInt(10);
                if (roll < 2 || model.lots.isEmpty()) {
                    // A receipt. Usually the newest lot; sometimes an OLDER one that arrives late.
                    Lot fresh = lot(new UUID(2, lotNo++), 1 + random.nextInt(6));
                    int at = random.nextInt(4) == 0 ? random.nextInt(model.lots.size() + 1) : model.lots.size();
                    model.lots.add(at, fresh);
                    model.apply(model.conform());
                } else if (roll < 6) {
                    int free = model.conform().remainingByLot().values().stream().mapToInt(Integer::intValue).sum() - heldPieces.size();
                    if (free > 0) {
                        int qty = 1 + random.nextInt(Math.min(free, 5));
                        UUID a = model.record(Kind.DRAIN, qty, null);
                        drains.add(a);
                        outstanding.put(a, qty);
                    }
                } else if (roll < 8 && !drains.isEmpty()) {
                    UUID target = drains.get(random.nextInt(drains.size()));
                    int left = outstanding.get(target);
                    int what = random.nextInt(4);
                    if (what == 0 && !heldPieces.isEmpty()) {
                        // A returned piece is decided: fine (released) or it leaves (written off, marked down).
                        UUID piece = heldPieces.remove(random.nextInt(heldPieces.size()));
                        model.record(random.nextBoolean() ? Kind.RELEASE : Kind.REDRAW, 1, null, piece);
                    } else if (what == 1 && left > 0) {
                        // One piece of a sale comes back and is held.
                        heldPieces.add(model.record(Kind.RESTORE_HELD, 1, target));
                        outstanding.put(target, left - 1);
                    } else if (left > 0) {
                        int qty = 1 + random.nextInt(left);
                        model.record(Kind.RESTORE, qty, target);
                        outstanding.put(target, left - qty);
                    }
                } else if (roll < 9) {
                    // A corrected receipt: one lot is replaced by a new lot of the same size (a new cost), keeping its place.
                    int idx = random.nextInt(model.lots.size());
                    model.lots.set(idx, lot(new UUID(2, lotNo++), model.lots.get(idx).qtyReceived()));
                    model.apply(model.conform());
                }
                assertConsistent(model, seed, step);
            }
        }
    }

    private static void assertConsistent(Model model, long seed, int step) {
        String where = "seed " + seed + " step " + step;
        Outcome again = model.conform();
        assertThat(again.recuts()).as("conforming twice changes nothing, " + where).isEmpty();

        Map<UUID, Integer> net = new HashMap<>();
        for (Action a : model.actions) {
            int sign = a.kind() == Kind.DRAIN || a.kind() == Kind.REDRAW ? 1 : -1;
            for (LiveChunk c : model.liveByAction.get(a.id())) {
                net.merge(c.lotId(), sign * c.qty(), Integer::sum);
            }
            int moved = model.liveByAction.get(a.id()).stream().mapToInt(LiveChunk::qty).sum();
            assertThat(moved).as("an action moves exactly its quantity (a release moves nothing), " + where)
                    .isEqualTo(a.kind() == Kind.RELEASE ? 0 : a.qty());
        }
        for (Lot lot : model.lots) {
            int consumed = net.getOrDefault(lot.id(), 0);
            assertThat(again.remainingByLot().get(lot.id())).as("lot " + lot.id() + " adds up, " + where)
                    .isEqualTo(lot.qtyReceived() - consumed).isBetween(0, lot.qtyReceived());
        }
        for (UUID lotId : net.keySet()) {
            assertThat(model.lots.stream().anyMatch(l -> l.id().equals(lotId)))
                    .as("nothing is left drawn from a lot that is no longer current, " + where).isTrue();
        }
    }
}
