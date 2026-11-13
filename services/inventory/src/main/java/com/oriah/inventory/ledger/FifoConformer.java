package com.oriah.inventory.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Works out what a variant's stock ledger SHOULD say, and what must be re-cut to make it say so.
 *
 * <h2>The idea</h2>
 *
 * Oldest-first (FIFO) cost flow is a pure function of two things: the variant's lots, in FIFO order, and its
 * actions (sales, write-offs, give-aways that DRAIN lots; take-backs that RESTORE what a drain took), in the order
 * they were RECORDED. So rather than a special case for each thing that can change the lots (an older receipt that
 * arrives late, a corrected cost that replaces a lot, a take-back after either), the ledger is simply made to equal
 * that function's output. Anything already right is left alone; anything that is not is re-cut.
 *
 * <h2>What it does</h2>
 *
 * <ol>
 *   <li>Every lot starts whole. Actions are replayed in recording order ({@link Action#seq()}).</li>
 *   <li>A drain of {@code n} units draws from the oldest lot with stock, then the next, one chunk per lot.</li>
 *   <li>A restore of {@code m} units returns units to the NEWEST-drained chunks of the drain it restores,
 *       at the lot they came out of (the greatest lot in FIFO order first).</li>
 *   <li>For each action the simulated chunks are compared with the LIVE rows the ledger holds. Equal: untouched.
 *       Different: the live rows are to be reversed and the simulated chunks written as replacements.</li>
 *   <li>A restore is re-cut whenever the drain it restores is, even if its own chunks look the same: it must
 *       point at a live chunk of the new drain.</li>
 * </ol>
 *
 * <p>Pure: plain values in, plain values out, no database, no clock. The database layer turns an {@link Outcome}
 * into ledger rows. Running it twice over its own result changes nothing (idempotent), which the tests prove.
 */
public final class FifoConformer {

    private FifoConformer() {
    }

    /** A lot as the engine needs it: its identity and how many units it was received with. Passed in FIFO order. */
    public record Lot(UUID id, int qtyReceived) {
    }

    public enum Kind { DRAIN, RESTORE }

    /**
     * Something that took stock out ({@code DRAIN}) or put some back ({@code RESTORE}, naming the drain it restores).
     * {@code seq} is recording order.
     */
    public record Action(UUID id, long seq, Kind kind, int qty, UUID restoresActionId) {
    }

    /** {@code qty} units of one lot. */
    public record Chunk(UUID lotId, int qty) {
    }

    /** A row the ledger holds now and has not reversed: its identity, its action, its lot, and its magnitude. */
    public record LiveChunk(long movementId, UUID actionId, UUID lotId, int qty) {
    }

    /** What to do for one action: reverse these live rows, then write these chunks. */
    public record Recut(UUID actionId, List<Long> reversedMovementIds, List<Chunk> replacements) {
    }

    /**
     * @param recuts          the actions to re-cut, in recording order
     * @param remainingByLot  what each lot holds once the ledger is as it should be
     * @param desired         every action's chunks as they should be (drains in lot order, restores newest first)
     */
    public record Outcome(List<Recut> recuts, Map<UUID, Integer> remainingByLot, Map<UUID, List<Chunk>> desired) {
    }

    /** The lots cannot cover a drain. The caller refuses the action; it is never shortened. */
    public static class InsufficientLotsException extends IllegalStateException {
        public InsufficientLotsException(String message) {
            super(message);
        }
    }

    /** What of a drain is still out, chunk by chunk, newest last: what a take-back may return. */
    private static final class Outstanding {
        final UUID lotId;
        int qty;

        Outstanding(UUID lotId, int qty) {
            this.lotId = lotId;
            this.qty = qty;
        }
    }

    public static Outcome conform(List<Lot> lotsInFifoOrder, List<Action> actions, List<LiveChunk> live) {
        Map<UUID, Integer> order = new HashMap<>();
        Map<UUID, Integer> remaining = new LinkedHashMap<>();
        for (Lot lot : lotsInFifoOrder) {
            if (lot.qtyReceived() < 1) {
                throw new IllegalArgumentException("lot " + lot.id() + " was received with " + lot.qtyReceived() + " units");
            }
            if (order.put(lot.id(), order.size()) != null) {
                throw new IllegalArgumentException("lot " + lot.id() + " is listed twice");
            }
            remaining.put(lot.id(), lot.qtyReceived());
        }

        List<Action> inOrder = actions.stream().sorted(Comparator.comparingLong(Action::seq)).toList();
        Map<UUID, List<Chunk>> desired = new LinkedHashMap<>();
        Map<UUID, List<Outstanding>> stacks = new HashMap<>();

        for (Action action : inOrder) {
            if (action.qty() < 1) {
                throw new IllegalArgumentException("action " + action.id() + " moves " + action.qty() + " units");
            }
            if (action.kind() == Kind.DRAIN) {
                desired.put(action.id(), drain(action, lotsInFifoOrder, remaining, stacks));
            } else {
                desired.put(action.id(), restore(action, remaining, stacks));
            }
        }

        Map<UUID, List<LiveChunk>> liveByAction = new HashMap<>();
        for (LiveChunk chunk : live) {
            liveByAction.computeIfAbsent(chunk.actionId(), k -> new ArrayList<>()).add(chunk);
        }

        Set<UUID> recut = new LinkedHashSet<>();
        for (Action action : inOrder) {
            if (!sameChunks(desired.get(action.id()), liveByAction.getOrDefault(action.id(), List.of()), order)) {
                recut.add(action.id());
            }
        }
        // A restore must point at a live chunk of the drain it restores: re-cut with it.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Action action : inOrder) {
                if (action.kind() == Kind.RESTORE && !recut.contains(action.id())
                        && recut.contains(action.restoresActionId())) {
                    recut.add(action.id());
                    grew = true;
                }
            }
        }

        List<Recut> recuts = new ArrayList<>();
        for (Action action : inOrder) {
            if (recut.contains(action.id())) {
                List<Long> reversed = liveByAction.getOrDefault(action.id(), List.of()).stream()
                        .map(LiveChunk::movementId).sorted().toList();
                recuts.add(new Recut(action.id(), reversed, desired.get(action.id())));
            }
        }
        return new Outcome(List.copyOf(recuts), Map.copyOf(remaining), Map.copyOf(desired));
    }

    private static List<Chunk> drain(Action action, List<Lot> lots, Map<UUID, Integer> remaining,
                                     Map<UUID, List<Outstanding>> stacks) {
        List<Chunk> chunks = new ArrayList<>();
        List<Outstanding> stack = new ArrayList<>();
        int need = action.qty();
        for (Lot lot : lots) {
            int available = remaining.get(lot.id());
            if (available > 0) {
                int take = Math.min(available, need);
                remaining.put(lot.id(), available - take);
                chunks.add(new Chunk(lot.id(), take));
                stack.add(new Outstanding(lot.id(), take));
                need -= take;
                if (need == 0) {
                    break;
                }
            }
        }
        if (need > 0) {
            throw new InsufficientLotsException("action " + action.id() + " drains " + action.qty()
                    + " units but the lots can cover only " + (action.qty() - need));
        }
        stacks.put(action.id(), stack);
        return chunks;
    }

    private static List<Chunk> restore(Action action, Map<UUID, Integer> remaining, Map<UUID, List<Outstanding>> stacks) {
        List<Outstanding> stack = stacks.get(action.restoresActionId());
        if (stack == null) {
            throw new IllegalStateException("action " + action.id() + " restores " + action.restoresActionId()
                    + ", which was not recorded before it");
        }
        List<Chunk> chunks = new ArrayList<>();
        int need = action.qty();
        for (int i = stack.size() - 1; i >= 0 && need > 0; i--) {
            Outstanding out = stack.get(i);
            int take = Math.min(out.qty, need);
            if (take > 0) {
                out.qty -= take;
                remaining.merge(out.lotId, take, Integer::sum);
                chunks.add(new Chunk(out.lotId, take));
                need -= take;
            }
        }
        if (need > 0) {
            throw new IllegalStateException("action " + action.id() + " restores " + action.qty() + " units but only "
                    + (action.qty() - need) + " of " + action.restoresActionId() + " are outstanding");
        }
        return chunks;
    }

    /** Same lots and quantities, compared in FIFO order (a lot that is no longer current sorts last). */
    private static boolean sameChunks(List<Chunk> wanted, List<LiveChunk> held, Map<UUID, Integer> order) {
        if (wanted.size() != held.size()) {
            return false;
        }
        Comparator<UUID> byLot = Comparator.comparingInt((UUID id) -> order.getOrDefault(id, Integer.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder());
        List<Chunk> a = wanted.stream().sorted(Comparator.comparing(Chunk::lotId, byLot)).toList();
        List<LiveChunk> b = held.stream().sorted(Comparator.comparing(LiveChunk::lotId, byLot)).toList();
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).lotId().equals(b.get(i).lotId()) || a.get(i).qty() != b.get(i).qty()
                    || !order.containsKey(b.get(i).lotId())) {
                return false;
            }
        }
        return true;
    }
}
