package com.oriah.inventory.model;

import java.util.UUID;

import com.oriah.inventory.exception.InsufficientStockException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.oriah.jpa.BaseEntity;

/**
 * Inventory aggregate for a single product variant.
 *
 * <p>Holds two counts and the invariants that hold them in line:
 * <ul>
 *   <li>{@code onHand} — physical units held. It is the single count of the thing, and it equals what the cost
 *       lots hold: it goes UP only when a received consignment brings lots ({@link #receive}) or a take-back
 *       returns units to one ({@link #takeBack}), and DOWN when a sale ({@link #confirm}) or a write-off
 *       ({@link #writeOff}) drains them. The lots themselves are kept by the stock ledger; the service that
 *       moves units checks after every change that the lots add up to this count.</li>
 *   <li>{@code reserved} — units claimed by carts that haven't shipped
 *       yet. Goes up on reserve, down on release (abandon) or confirm
 *       (ship). Invariant: {@code 0 <= reserved <= onHand}.</li>
 *   <li>{@code committed} — boxes set aside for products (packaging only): a PROMISE, not a movement. It reserves a
 *       quantity and never a lot, so it touches no cost lot. Invariant: {@code reserved + committed <= onHand}.</li>
 *   <li>{@code free = onHand - committed - reserved} — what can still be promised or reserved; computed, not stored, and
 *       never below zero. {@link #available()} is the same figure under its older name.</li>
 * </ul>
 *
 * <p>The trap is promising the same box twice: 50 pouches to the gold studs and 50 to the silver ones out of 60 held. So
 * {@link #commit} needs free boxes, a reservation sees only what is free, and a write-off takes free boxes first and cuts
 * into what is promised only for what free cannot absorb.
 *
 * <p>A stock row exists only because goods were received: there is no way to create one with a count, because a
 * count with no lots has no cost, and stock with no cost makes profit wrong.
 *
 * <p>The DB has matching {@code CHECK} constraints as defence in depth
 * against a bug bypassing the domain rules below.
 */
@Entity
@Table(name = "inventory")
public class Inventory extends BaseEntity {

    @Id
    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    /** Boxes promised to products; always 0 for goods. See the class comment. */
    @Column(name = "committed", nullable = false)
    private int committed;

    /** {@code SELLABLE} or {@code PACKAGING}, taken from the receipt that created the row; never changes. */
    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    /**
     * Optimistic lock. Each save checks that the row still has the version
     * this copy was read with, and bumps it; a save from a stale read is
     * refused at commit (409 to the caller), so two concurrent stock changes
     * can never both apply. A wrapper type, so a new row (null) is inserted
     * rather than merged. Never set by application code.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Inventory() {
        // For JPA hydration only — do not call from application code.
    }

    private Inventory(UUID variantId, String kind) {
        this.variantId = variantId;
        this.kind = kind;
        this.onHand = 0;
        this.reserved = 0;
    }

    /**
     * A stock row for a variant that has just been received for the first time. It holds nothing until
     * {@link #receive} is called with the units the lots were made for.
     */
    public static Inventory open(UUID variantId, String kind) {
        if (variantId == null) {
            throw new IllegalArgumentException("variantId must not be null");
        }
        if (!"SELLABLE".equals(kind) && !"PACKAGING".equals(kind)) {
            throw new IllegalArgumentException("kind must be SELLABLE or PACKAGING, was " + kind);
        }
        return new Inventory(variantId, kind);
    }

    public UUID variantId() {
        return variantId;
    }

    public int onHand() {
        return onHand;
    }

    public int reserved() {
        return reserved;
    }

    public String kind() {
        return kind;
    }

    public int committed() {
        return committed;
    }

    /** What can still be promised to a product or reserved by an order: held, less what is set aside and what is reserved. */
    public int free() {
        return onHand - reserved - committed;
    }

    /** What an order may still reserve: the same as {@link #free()}. Boxes promised to a product are not for the next order to take. */
    public int available() {
        return free();
    }

    /**
     * Sets {@code qty} boxes aside for a product. They stay held (the count does not change); they stop being free.
     *
     * @throws IllegalStateException      if this row is goods: only packaging is set aside (409)
     * @throws InsufficientStockException if fewer than {@code qty} are free
     */
    public void commit(int qty) {
        requirePositive(qty);
        if (!"PACKAGING".equals(kind)) {
            throw new IllegalStateException("only packaging is set aside for products, and this is " + kind);
        }
        if (qty > free()) {
            throw InsufficientStockException.cannot("set aside", qty, free(), "free");
        }
        this.committed += qty;
    }

    /** Lets {@code qty} set-aside boxes go back to free. Nothing leaves the building. */
    public void uncommit(int qty) {
        requirePositive(qty);
        if (qty > committed) {
            throw InsufficientStockException.cannot("let go", qty, committed, "set aside");
        }
        this.committed -= qty;
    }

    /**
     * A sale of boxes that were set aside for the product and never reserved: the set-aside and the count both fall. If only the
     * count fell, the promise would outlive the box it was for and what is set aside would drift upward until nothing could be promised.
     */
    public void consumeCommitted(int qty) {
        requirePositive(qty);
        if (qty > committed) {
            throw InsufficientStockException.cannot("sell", qty, committed, "set aside");
        }
        this.committed -= qty;
        this.onHand -= qty;
    }

    /**
     * Reserves {@code qty} units against this variant. Used when a customer
     * adds the variant to their cart.
     *
     * @throws IllegalArgumentException if {@code qty <= 0} (a malformed argument, 400)
     * @throws InsufficientStockException if not enough stock is available (a state
     *         conflict, 409)
     */
    public void reserve(int qty) {
        requirePositive(qty);
        if (qty > available()) {
            throw InsufficientStockException.cannot("reserve", qty, available(), "available");
        }
        this.reserved += qty;
    }

    /**
     * Releases {@code qty} units back from the reserved pool — e.g. on
     * cart abandon or order cancel before shipment.
     *
     * @throws IllegalArgumentException if {@code qty <= 0} (a malformed argument, 400)
     * @throws InsufficientStockException if it exceeds {@code reserved} (a state
     *         conflict, 409)
     */
    public void release(int qty) {
        requirePositive(qty);
        if (qty > reserved) {
            throw InsufficientStockException.cannot("release", qty, reserved, "reserved");
        }
        this.reserved -= qty;
    }

    /**
     * Confirms {@code qty} units sold: decrements BOTH {@code reserved}
     * and {@code onHand}. The reservation is converted into a permanent
     * stock decrement (the cost lots are drained in the same transaction by the stock ledger).
     *
     * @throws IllegalArgumentException if {@code qty <= 0} (a malformed argument, 400)
     * @throws InsufficientStockException if it exceeds {@code reserved} (a state
     *         conflict, 409)
     */
    public void confirm(int qty) {
        requirePositive(qty);
        if (qty > reserved) {
            throw InsufficientStockException.cannot("confirm", qty, reserved, "reserved");
        }
        this.reserved -= qty;
        this.onHand -= qty;
    }

    /**
     * Units received into lots: a received consignment line. Called only together with the lots it made, which
     * the service checks add up.
     */
    public void receive(int qty) {
        requirePositive(qty);
        this.onHand += qty;
    }

    /**
     * Units written off or given away: stock that was not reserved for anyone leaves with no sale. Free units go first; only for
     * what free cannot absorb are boxes that were set aside for products taken, and the caller is told how many, so it can cut the
     * records and name the products.
     *
     * @return how many of the units written off were set aside (0 for goods, and for boxes free could absorb)
     * @throws InsufficientStockException if more than is held and not reserved (a state conflict, 409)
     */
    public int writeOff(int qty) {
        requirePositive(qty);
        int unreserved = onHand - reserved;
        if (qty > unreserved) {
            throw InsufficientStockException.cannot("write off", qty, unreserved, "available");
        }
        int cut = Math.max(0, qty - free());
        this.committed -= cut;
        this.onHand -= qty;
        return cut;
    }

    /** Units that were written off or given away come back, to the lots they came out of. */
    public void takeBack(int qty) {
        requirePositive(qty);
        this.onHand += qty;
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }
    }
}
