package com.oriah.inventory.model;

import java.util.UUID;

import com.oriah.inventory.exception.InsufficientStockException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.AuditableEntity;

/**
 * Inventory aggregate for a single product variant.
 *
 * <p>Holds two counts and the invariants that hold them in line:
 * <ul>
 *   <li>{@code onHand} — physical units in the warehouse. Goes up on
 *       restock, down on ship-confirmation.</li>
 *   <li>{@code reserved} — units claimed by carts that haven't shipped
 *       yet. Goes up on reserve, down on release (abandon) or confirm
 *       (ship). Invariant: {@code 0 <= reserved <= onHand}.</li>
 *   <li>{@code available = onHand - reserved} — computed, not stored.</li>
 * </ul>
 *
 * <p>The DB has matching {@code CHECK} constraints as defence in depth
 * against a bug bypassing the domain rules below.
 */
@Entity
@Table(name = "inventory")
public class Inventory extends AuditableEntity {

    @Id
    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    protected Inventory() {
        // For JPA hydration only — do not call from application code.
    }

    /**
     * Creates an inventory row with {@code reserved = 0}. Used when seeding
     * initial stock or when restocking a variant we haven't seen before.
     */
    public Inventory(UUID variantId, int onHand) {
        if (variantId == null) {
            throw new IllegalArgumentException("variantId must not be null");
        }
        if (onHand < 0) {
            throw new IllegalArgumentException("onHand must be >= 0");
        }
        this.variantId = variantId;
        this.onHand = onHand;
        this.reserved = 0;
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

    public int available() {
        return onHand - reserved;
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
     * Confirms {@code qty} units shipped: decrements BOTH {@code reserved}
     * and {@code onHand}. The reservation is converted into a permanent
     * stock decrement.
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
     * Adds {@code qty} units to {@code onHand} — used when a warehouse
     * restock comes in.
     */
    public void restock(int qty) {
        requirePositive(qty);
        this.onHand += qty;
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }
    }
}
