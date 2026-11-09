package com.oriah.order.model;

import com.oriah.money.Ngwee;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import jakarta.persistence.Version;

import com.oriah.jpa.BaseEntity;
import com.oriah.quote.Quote;

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

    /**
     * What delivery costs on this order; NULL on an order placed before V4,
     * where it is unknown -- never a claim that it was zero.
     */
    @Column(name = "shipping_amount")
    private BigDecimal shippingAmount;

    /** The order's discount; NULL before V4 for the same reason. Zero until Phase 7. */
    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    /** The option the shopper chose, e.g. SELF_COLLECT; NULL before V4. */
    @Column(name = "delivery_option", length = 64)
    private String deliveryOption;

    /**
     * True on every order placed from V4 on: its total is subtotal + shipping
     * - discount, which the database enforces (orders_totals_authoritative_chk)
     * and payment verification checks the amount against (Phase 0, item 4.5).
     */
    @Column(name = "totals_authoritative", nullable = false)
    private boolean totalsAuthoritative;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking (V5). Every change to an order checks that nobody
     * changed it since it was read: paying and cancelling at the same moment
     * both read CREATED, and from CREATED both moves are legal, so without
     * this both would win and the event log would say the order was paid AND
     * cancelled. With it, the second write is refused (409) and appends
     * nothing. A wrapper, so Spring Data sees an unsaved order as new.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    private Order(String keycloakId, UUID shippingAddressId, String currency) {
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

    /**
     * A new order whose totals are authoritative (Phase 0, item 4): the
     * shipping it carries was priced by order itself, from the option the
     * shopper chose, and its total is subtotal + shipping - discount.
     *
     * @param shippingAddressId may be null: self-collect needs no address
     * @param deliveryOption    an option code, e.g. SELF_COLLECT
     * @param shippingAmount    what that option costs, in whole ngwee; K0 for self-collect
     */
    public Order(String keycloakId, UUID shippingAddressId, String currency, String deliveryOption,
                 BigDecimal shippingAmount) {
        this(keycloakId, shippingAddressId, currency);
        if (deliveryOption == null || !deliveryOption.matches(Quote.DELIVERY_OPTION_PATTERN)) {
            throw new IllegalArgumentException("deliveryOption must be an option code such as SELF_COLLECT");
        }
        Ngwee.require(shippingAmount, "shippingAmount");
        if (shippingAmount.signum() < 0) {
            throw new IllegalArgumentException("shippingAmount must not be negative");
        }
        this.deliveryOption = deliveryOption;
        this.shippingAmount = shippingAmount.setScale(2, RoundingMode.UNNECESSARY);
        // Zero until Phase 7, when a promotion's discount is worked out per
        // piece and stored per line (CLIENT_DECISIONS.md section 1.1).
        this.discountAmount = BigDecimal.ZERO.setScale(2);
        this.totalsAuthoritative = true;
        // Before any line is added, the total is already the delivery charge
        // -- a marked order must never hold a total that breaks the formula.
        recomputeTotals();
    }

    public BigDecimal shippingAmount() { return shippingAmount; }
    public BigDecimal discountAmount() { return discountAmount; }
    public String deliveryOption() { return deliveryOption; }
    public boolean totalsAuthoritative() { return totalsAuthoritative; }

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
        Ngwee.require(unitPrice, "unitPrice");
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

    /**
     * subtotal + shipping - discount: the same formula as the shared
     * {@code Quote.total()}, which OrderTotalsTest holds the two to. An
     * order built by the old constructor carries no shipping or discount
     * (unknown, not zero) and is not marked, so its total stays its subtotal.
     */
    private void recomputeTotals() {
        BigDecimal sub = items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.subtotal = sub;
        this.total = totalsAuthoritative
                ? sub.add(shippingAmount).subtract(discountAmount).setScale(2, RoundingMode.UNNECESSARY)
                : sub;
    }
}
