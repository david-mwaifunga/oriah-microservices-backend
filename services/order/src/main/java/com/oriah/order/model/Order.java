package com.oriah.order.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * Order aggregate. Created from a cart at checkout time; immutable
 * line items, mutable status (driven by {@link OrderStatus} FSM).
 *
 * <p>Mapped to the {@code orders} table — not {@code order}, because
 * "order" is a SQL reserved word.
 */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "keycloak_id", nullable = false)
    private String keycloakId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    @Column(name = "shipping_address_id")
    private UUID shippingAddressId;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public Order(String keycloakId, UUID shippingAddressId, String currency) {
        this.id = UUID.randomUUID();
        this.keycloakId = keycloakId;
        this.shippingAddressId = shippingAddressId;
        // Fails fast rather than defaulting. The old default was "USD" on a
        // platform priced in Kwacha, and it was the last hardcoded currency
        // left in live code -- cart and catalog hold DEFAULT_CURRENCY = "ZMW",
        // shipping's is configuration, payment's identical default went in the
        // same pass.
        //
        // Refusal rather than ZMW, because an order is the RECORD of a sale:
        // its currency should be the one the sale was priced in, and a
        // default -- even the right one -- produces an order whose currency
        // nobody chose. Whoever supplies it, the caller today or catalog once
        // order prices orders itself, an order that does not know what unit
        // its money is in is not an order.
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "An order must name its currency; money with no unit is not an amount.");
        }
        this.currency = currency;
        this.status = OrderStatus.CREATED;
        this.subtotal = BigDecimal.ZERO;
        this.total = BigDecimal.ZERO;
        this.placedAt = Instant.now();
        this.updatedAt = placedAt;
    }

    public UUID id() { return id; }
    public String keycloakId() { return keycloakId; }
    public OrderStatus status() { return status; }
    public BigDecimal subtotal() { return subtotal; }
    public BigDecimal total() { return total; }
    public String currency() { return currency; }
    public UUID paymentIntentId() { return paymentIntentId; }
    public UUID shippingAddressId() { return shippingAddressId; }
    public Instant placedAt() { return placedAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<OrderItem> items() { return Collections.unmodifiableList(items); }

    public OrderItem addItem(UUID variantId, String title, int quantity, BigDecimal unitPrice) {
        OrderItem item = new OrderItem(this, variantId, title, quantity, unitPrice);
        items.add(item);
        recomputeTotals();
        return item;
    }

    public void attachPaymentIntent(UUID paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
        this.updatedAt = Instant.now();
    }

    /**
     * Drive the FSM. Throws if the requested transition isn't allowed
     * from the current state.
     */
    public void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Cannot transition order " + id + " from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    private void recomputeTotals() {
        BigDecimal sub = items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.subtotal = sub;
        this.total = sub; // no shipping/discount yet
    }
}
