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

    /** The shopper who owns this order; null only on a PHONE order (staff recorded it for a caller with no account). */
    @Column(name = "keycloak_id")
    private String keycloakId;

    // How the order was taken, and what a recorded order adds (Phase 9; V13). All written once, at recording, and never through the entity afterwards.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Channel channel = Channel.WEB;

    /** {@code user:<keycloak subject>} of the member of staff who recorded it; null on a web order. */
    @Column(name = "recorded_by", length = 128, updatable = false)
    private String recordedBy;

    @Column(name = "caller_name", length = 120, updatable = false)
    private String callerName;

    @Column(name = "caller_phone", length = 16, updatable = false)
    private String callerPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_kind", length = 12, updatable = false)
    private SaleKind saleKind;

    /** When the sale happened; placed_at stays the time it was keyed. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "backdate_reason", length = 200, updatable = false)
    private String backdateReason;

    @Column(name = "slip_reference", length = 40, updatable = false)
    private String slipReference;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    /** PUBLISHED (the shop's town price) or AGREED (keyed by staff for a delivery agreed by phone). */
    @Column(name = "delivery_price_source", nullable = false, length = 10, updatable = false)
    private String deliveryPriceSource = "PUBLISHED";

    /** The delivery charge agreed by phone, as agreed: written once and never changed (the live charge, shipping_amount, may be waived). */
    @Column(name = "delivery_agreed_amount", updatable = false)
    private BigDecimal deliveryAgreedAmount;

    @Column(name = "delivery_agreed_reason", length = 200, updatable = false)
    private String deliveryAgreedReason;

    /** What the shop's own published price for this town and option was when a LIVE delivery was agreed (finance reports the concession); null when there was none. */
    @Column(name = "delivery_quoted_amount", updatable = false)
    private BigDecimal deliveryQuotedAmount;

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

    /** What delivery WOULD have cost when staff waived it (the delivery revenue given up); null when it was not waived. Set with the next three, or not at all. */
    @Column(name = "delivery_waived_amount")
    private BigDecimal deliveryWaivedAmount;

    /** Who waived the delivery, {@code user:<keycloak subject>}. */
    @Column(name = "delivery_waived_by", length = 128)
    private String deliveryWaivedBy;

    @Column(name = "delivery_waiver_reason", length = 200)
    private String deliveryWaiverReason;

    @Column(name = "delivery_waived_at")
    private Instant deliveryWaivedAt;

    /** The town the delivery was priced for; null for self-collect and for an order placed before Phase 7. */
    @Column(name = "delivery_town_id")
    private UUID deliveryTownId;

    // The delivery as it was when the order was placed (Phase 8; V10): who it is for and where it goes. Written once, at placement, from what the shopper
    // typed and the address customer answered with; never changed through the entity (a correction before dispatch is SQL, with its history kept). Null for
    // a pickup and for an order placed before V10. Never in an event or a log.
    @Column(name = "recipient_name", length = 120, updatable = false)
    private String recipientName;

    @Column(name = "recipient_phone", length = 16, updatable = false)
    private String recipientPhone;

    @Column(name = "address_line1", updatable = false)
    private String addressLine1;

    @Column(name = "address_line2", updatable = false)
    private String addressLine2;

    @Column(name = "address_city", length = 120, updatable = false)
    private String addressCity;

    @Column(name = "address_state", length = 120, updatable = false)
    private String addressState;

    @Column(name = "address_postal_code", length = 20, updatable = false)
    private String addressPostalCode;

    @Column(name = "address_country", length = 2, updatable = false)
    private String addressCountry;

    /** What shipping called the option when it was priced (e.g. "Yango"): the carrier, as far as the packer is concerned. */
    @Column(name = "delivery_option_name", length = 80, updatable = false)
    private String deliveryOptionName;

    @Column(name = "delivery_town_name", length = 120, updatable = false)
    private String deliveryTownName;

    /** Who pays the courier: ORIAH_COLLECTS or RECIPIENT_PAYS (K0 on this order); null for self-collect and older orders. */
    @Column(name = "delivery_payer", length = 16)
    private String deliveryPayer;

    /** The hold at inventory, minted here BEFORE inventory is asked, so a repeated ask names the same one. Null on a legacy order. */
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "hold_state", length = 12)
    private HoldState holdState;

    @Column(name = "held_at")
    private Instant heldAt;

    /** When the hold lapses, as inventory last said. */
    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    /** The fingerprint (oriah-quote/2) of the price the shopper was shown: the same shopper and bag are one live order. */
    @Column(name = "quote_digest", length = 64)
    private String quoteDigest;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    /** A claim was submitted and the stretch of the hold is not yet confirmed by inventory: the hold job finishes it. */
    @Column(name = "hold_extension_pending", nullable = false)
    private boolean holdExtensionPending;

    /**
     * The payment through ZynlePay in flight for this order (Phase 13), or null. While it is set nothing else may pay or end the order. It has no
     * clock: {@link #endProviderPayment} (the attempt FAILED) and {@link #beginSettlement} (money is being applied) let it go.
     */
    @Column(name = "provider_payment_id")
    private UUID providerPaymentId;

    /** What the shopper SAID they paid with. Not money: staff confirm money, or reject the claim. */
    @Column(name = "claim_id")
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_method", length = 16)
    private ClaimMethod claimMethod;

    /** The reference in its one spelling ({@link PaymentReference}). */
    @Column(name = "claim_reference", length = 64)
    private String claimReference;

    @Column(name = "claim_submitted_at")
    private Instant claimSubmittedAt;

    /** A confirmed payment is being applied to this order (the settlement saga): the order's own actions answer 409 until it ends. */
    @Column(name = "settling_confirmation_id")
    private UUID settlingConfirmationId;

    /** The confirmation of money that PAID the sale; null until it is paid (and on an order paid before confirmations existed). */
    @Column(name = "paid_confirmation_id")
    private UUID paidConfirmationId;

    /** The day the money ARRIVED: the date of the sale, which is not the day it was keyed or applied. */
    @Column(name = "paid_at")
    private Instant paidAt;

    /** Ours, never a tax invoice. */
    @Column(name = "receipt_number", length = 24)
    private String receiptNumber;

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
        if (keycloakId == null || keycloakId.isBlank()) {
            // Only a recorded (PHONE) order has no owner, and it is built by Order.recorded; the database refuses the same (orders_owner_by_channel).
            throw new IllegalArgumentException("A web order needs its owner: the shopper who placed it.");
        }
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
        this.occurredAt = placedAt;
    }

    /** The order of a caller with no account, recorded by staff: no owner, the same delivery and totals rules as any order. Built by {@link #recorded}. */
    private Order(String currency, Void recorded) {
        this.id = UUID.randomUUID();
        this.channel = Channel.PHONE;
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("An order must name its currency; money with no unit is not an amount.");
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
        this(keycloakId, shippingAddressId, currency, deliveryOption, shippingAmount, null, null);
    }

    /**
     * A new order as Phase 7 places it: delivery priced for a town, by a payer. For self-collect both are null. When the recipient pays the
     * courier the amount is K0 -- the order records what is OWED to Oriah, and the fare is not that.
     *
     * @param deliveryTownId the town shipping priced; null for self-collect
     * @param deliveryPayer  ORIAH_COLLECTS or RECIPIENT_PAYS; null for self-collect
     */
    public Order(String keycloakId, UUID shippingAddressId, String currency, String deliveryOption,
                 BigDecimal shippingAmount, UUID deliveryTownId, String deliveryPayer) {
        this(keycloakId, shippingAddressId, currency);
        applyDelivery(deliveryOption, shippingAmount, deliveryTownId, deliveryPayer);
    }

    /** The delivery of any new order: its option, charge, town and payer, judged by the same rules whoever places it. */
    private void applyDelivery(String deliveryOption, BigDecimal shippingAmount, UUID deliveryTownId, String deliveryPayer) {
        if (deliveryPayer != null && !deliveryPayer.equals("ORIAH_COLLECTS") && !deliveryPayer.equals("RECIPIENT_PAYS")) {
            throw new IllegalArgumentException("deliveryPayer must be ORIAH_COLLECTS or RECIPIENT_PAYS");
        }
        if ("RECIPIENT_PAYS".equals(deliveryPayer) && shippingAmount != null && shippingAmount.signum() != 0) {
            throw new IllegalArgumentException("a courier the recipient pays costs nothing on this order");
        }
        if ("OWN_DELIVERY".equals(deliveryOption) && "RECIPIENT_PAYS".equals(deliveryPayer)) {
            // Oriah's own delivery is income, and a driver carrying the customer's cash is a float nobody wants (CLIENT_DECISIONS Q27). Shipping never
            // prices it that way; the order refuses it too, so no channel (a phone order, Phase 9) can record one.
            throw new IllegalArgumentException("Oriah's own delivery is paid to Oriah with the order, never by the recipient on arrival");
        }
        this.deliveryTownId = deliveryTownId;
        this.deliveryPayer = deliveryPayer;
        if (deliveryOption == null || !deliveryOption.matches(Quote.DELIVERY_OPTION_PATTERN)) {
            throw new IllegalArgumentException("deliveryOption must be an option code such as SELF_COLLECT");
        }
        Ngwee.require(shippingAmount, "shippingAmount");
        if (shippingAmount.signum() < 0) {
            throw new IllegalArgumentException("shippingAmount must not be negative");
        }
        this.deliveryOption = deliveryOption;
        this.shippingAmount = shippingAmount.setScale(2, RoundingMode.UNNECESSARY);
        // A promotion's discount is worked out per piece and stored per line
        // (CLIENT_DECISIONS.md section 1.1); the order's discount is the sum of
        // those, so it starts at zero and every line added moves it.
        this.discountAmount = BigDecimal.ZERO.setScale(2);
        this.totalsAuthoritative = true;
        // Before any line is added, the total is already the delivery charge
        // -- a marked order must never hold a total that breaks the formula.
        recomputeTotals();
    }

    /**
     * A new order staff record for a caller with no account (Phase 9): no owner, the caller and recorder kept, dated to when the sale happened. Every rule the
     * database holds about it (V13) is refused here first, in plain words.
     *
     * @param sale            who and what: see {@link RecordedSale}
     * @param deliveryOption  {@code SELF_COLLECT}, or the option agreed or priced
     * @param shippingAmount  the delivery charge: K0.00 for a pickup or a recipient-paid courier, the published price, or the agreed amount
     * @param deliveryTownId  the town; required for any delivery
     * @param deliveryPayer   ORIAH_COLLECTS or RECIPIENT_PAYS; null for a pickup
     * @throws IllegalArgumentException anything that makes no sense
     */
    public static Order recorded(RecordedSale sale, String currency, String deliveryOption, BigDecimal shippingAmount, UUID deliveryTownId, String deliveryPayer) {
        if (sale == null || sale.recordedBy() == null || sale.recordedBy().isBlank()) {
            throw new IllegalArgumentException("A recorded order names the member of staff who recorded it.");
        }
        if (sale.callerName() == null || sale.callerName().isBlank()) {
            throw new IllegalArgumentException("A recorded order names its caller.");
        }
        if (sale.callerPhone() != null && !sale.callerPhone().matches("^\\+[0-9]{8,15}$")) {
            throw new IllegalArgumentException("A caller's phone number is + and 8 to 15 digits.");
        }
        if (sale.kind() == null) {
            throw new IllegalArgumentException("A recorded order says whether it is a live phone sale or a late paper sale.");
        }
        if (sale.requestHash() == null || !sale.requestHash().matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("A recorded order keeps the hash of the request it was made from.");
        }
        String reason = sale.backdateReason() == null ? null : sale.backdateReason().strip();
        boolean late = sale.kind() == SaleKind.LATE_PAPER;
        if (late != (reason != null) || (reason != null && (reason.length() < 5 || reason.length() > 200))) {
            throw new IllegalArgumentException("A late paper sale has a reason of 5 to 200 characters; a live sale has none.");
        }
        boolean pickup = Quote.SELF_COLLECT.equals(deliveryOption);
        if (sale.callerPhone() == null && !pickup) {
            throw new IllegalArgumentException("A delivery needs the caller's phone number.");
        }
        Order order = new Order(currency, null);
        if (sale.occurredAt() == null || sale.occurredAt().isAfter(order.placedAt.plusSeconds(300))) {
            throw new IllegalArgumentException("A sale cannot have happened in the future.");
        }
        order.applyDelivery(deliveryOption, shippingAmount, deliveryTownId, deliveryPayer);
        if (sale.agreedAmount() != null) {
            if (pickup || deliveryTownId == null) {
                throw new IllegalArgumentException("An agreed delivery is for a delivering option and a town.");
            }
            if (sale.agreedAmount().compareTo(order.shippingAmount) != 0) {
                throw new IllegalArgumentException("The agreed delivery is the order's delivery charge.");
            }
            if (Quote.ORIAH_COLLECTS.equals(deliveryPayer) ? sale.agreedAmount().signum() <= 0 : sale.agreedAmount().signum() != 0) {
                throw new IllegalArgumentException("An agreed delivery is more than K0.00 when Oriah collects it and K0.00 when the recipient pays the courier.");
            }
            order.deliveryPriceSource = "AGREED";
            order.deliveryAgreedAmount = sale.agreedAmount().setScale(2, RoundingMode.UNNECESSARY);
            order.deliveryAgreedReason = sale.agreedReason() == null ? null : sale.agreedReason().strip();
            if (sale.quotedAmount() != null) {
                if (sale.quotedAmount().signum() < 0) {
                    throw new IllegalArgumentException("The shop's quote for an agreed delivery is not negative.");
                }
                order.deliveryQuotedAmount = sale.quotedAmount().setScale(2, RoundingMode.UNNECESSARY);
            }
        } else if (sale.agreedReason() != null || sale.quotedAmount() != null) {
            throw new IllegalArgumentException("A reason or a quote for an agreed delivery needs an agreed delivery.");
        }
        order.recordedBy = sale.recordedBy();
        order.callerName = sale.callerName().strip();
        order.callerPhone = sale.callerPhone();
        order.saleKind = sale.kind();
        order.occurredAt = sale.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        order.backdateReason = reason;
        order.slipReference = sale.slipReference() == null || sale.slipReference().isBlank() ? null : sale.slipReference().strip();
        order.requestHash = sale.requestHash();
        return order;
    }

    public Channel channel() { return channel; }
    public String recordedBy() { return recordedBy; }
    public String callerName() { return callerName; }
    public String callerPhone() { return callerPhone; }
    public SaleKind saleKind() { return saleKind; }
    public Instant occurredAt() { return occurredAt; }
    public String backdateReason() { return backdateReason; }
    public String slipReference() { return slipReference; }
    public String requestHash() { return requestHash; }
    public String deliveryPriceSource() { return deliveryPriceSource; }
    public BigDecimal deliveryAgreedAmount() { return deliveryAgreedAmount; }
    public String deliveryAgreedReason() { return deliveryAgreedReason; }
    public BigDecimal deliveryQuotedAmount() { return deliveryQuotedAmount; }

    /**
     * Whether this shopper owns the order. A recorded order is owned by nobody, so it is no shopper's: {@code null}-safe, because the one place an owner is
     * null is exactly where a careless {@code equals} would throw instead of answering "not yours".
     */
    public boolean isOwnedBy(String subject) {
        return keycloakId != null && subject != null && keycloakId.equals(subject);
    }

    public String recipientName() { return recipientName; }
    public String recipientPhone() { return recipientPhone; }
    public String addressLine1() { return addressLine1; }
    public String addressLine2() { return addressLine2; }
    public String addressCity() { return addressCity; }
    public String addressState() { return addressState; }
    public String addressPostalCode() { return addressPostalCode; }
    public String addressCountry() { return addressCountry; }
    public String deliveryOptionName() { return deliveryOptionName; }
    public String deliveryTownName() { return deliveryTownName; }

    /**
     * An order that is cancelled forgets who it was for and where it was going: personal data is not kept for a parcel that will never be sent. Only in
     * memory: the columns are never written by the entity, and the same statement is made in SQL by whoever cancels (see OrderService).
     */
    public void forgetDelivery() {
        this.recipientName = null;
        this.recipientPhone = null;
        this.addressLine1 = null;
        this.addressLine2 = null;
        this.addressCity = null;
        this.addressState = null;
        this.addressPostalCode = null;
        this.addressCountry = null;
    }

    /** Whether the shopper collects: nothing is shipped or delivered, and no recipient or address is kept. */
    public boolean isSelfCollect() { return Quote.SELF_COLLECT.equals(deliveryOption); }

    /** Whether the packer has someone to hand the parcel to and somewhere to take it (an order placed before Phase 8 has neither). */
    public boolean hasDeliveryDetails() { return recipientName != null && addressLine1 != null; }

    public BigDecimal shippingAmount() { return shippingAmount; }
    public BigDecimal deliveryWaivedAmount() { return deliveryWaivedAmount; }
    public String deliveryWaivedBy() { return deliveryWaivedBy; }
    public String deliveryWaiverReason() { return deliveryWaiverReason; }
    public Instant deliveryWaivedAt() { return deliveryWaivedAt; }
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
    public UUID deliveryTownId() { return deliveryTownId; }
    public String deliveryPayer() { return deliveryPayer; }
    public UUID reservationId() { return reservationId; }
    public HoldState holdState() { return holdState; }
    public Instant heldAt() { return heldAt; }
    public Instant holdExpiresAt() { return holdExpiresAt; }
    public String quoteDigest() { return quoteDigest; }
    public String idempotencyKey() { return idempotencyKey; }
    public boolean holdExtensionPending() { return holdExtensionPending; }
    public UUID providerPaymentId() { return providerPaymentId; }
    public UUID claimId() { return claimId; }
    public ClaimMethod claimMethod() { return claimMethod; }
    public String claimReference() { return claimReference; }
    public Instant claimSubmittedAt() { return claimSubmittedAt; }
    public UUID settlingConfirmationId() { return settlingConfirmationId; }
    public UUID paidConfirmationId() { return paidConfirmationId; }
    public Instant paidAt() { return paidAt; }
    public String receiptNumber() { return receiptNumber; }

    /**
     * Confirmed money is about to be applied: the order's own actions (cancel, a staff move) answer 409 until {@link #endSettlement} or {@link #settle}.
     *
     * @throws IllegalStateException when another settlement is in flight, or the order is not waiting for money (CREATED, PAYMENT_SUBMITTED, EXPIRED)
     */
    public void beginSettlement(UUID confirmationId) {
        beginSettlement(confirmationId, null);
    }

    /**
     * @param itsAttempt the payment attempt this money came from, when a provider collected it (Phase 13); null for money a person confirmed.
     *                   The order lets go of the attempt it carries only for that attempt's own money: money of ANOTHER attempt (an earlier one
     *                   the owner recorded as paid after all) may still be cancelled before it pays the sale, and the attempt in flight could
     *                   then collect for an order that had been opened to a cancel or a second payment.
     */
    public void beginSettlement(UUID confirmationId, UUID itsAttempt) {
        if (settlingConfirmationId != null) {
            throw new IllegalStateException("order " + id + " is already being settled with " + settlingConfirmationId);
        }
        if (status != OrderStatus.CREATED && status != OrderStatus.PAYMENT_SUBMITTED && status != OrderStatus.EXPIRED) {
            throw new IllegalStateException("order " + id + " is " + status + " and takes no payment");
        }
        this.settlingConfirmationId = confirmationId;
        if (itsAttempt != null && itsAttempt.equals(providerPaymentId)) {
            // The attempt's own money is being applied: the attempt is over, and the settlement itself closes the order to everything else.
            this.providerPaymentId = null;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Takes an attempt to collect this order's total through ZynlePay: the order is closed to other money, and the stretch of its hold is still
     * to be confirmed by inventory ({@link #holdExtended} clears that).
     *
     * @throws IllegalStateException when the order is not CREATED and held, is having money applied, or already carries another attempt
     */
    public void takeProviderPayment(UUID attemptId) {
        if (status != OrderStatus.CREATED || holdState != HoldState.HELD || settlingConfirmationId != null) {
            throw new IllegalStateException("order " + id + " is not waiting for payment (" + status + ", hold " + holdState + ")");
        }
        this.providerPaymentId = attemptId;
        this.holdExtensionPending = true;
        this.updatedAt = Instant.now();
    }

    /**
     * The attempt is over without money (it FAILED, or the hold could not be stretched for it): the order is open again. Only the attempt the
     * order carries ends it; nothing waits to be stretched any more.
     *
     * @return whether this was the attempt the order carried
     */
    public boolean endProviderPayment(UUID attemptId) {
        if (providerPaymentId == null || !providerPaymentId.equals(attemptId)) {
            return false;
        }
        this.providerPaymentId = null;
        if (status == OrderStatus.CREATED) {
            this.holdExtensionPending = false;
        }
        this.updatedAt = Instant.now();
        return true;
    }

    /** The settlement in flight ended without paying the sale. */
    public void endSettlement() {
        this.settlingConfirmationId = null;
        this.updatedAt = Instant.now();
    }

    /**
     * The sale is paid: by the confirmation in flight, dated to the day its money arrived, with our receipt number. Moves to PAID (from CREATED,
     * PAYMENT_SUBMITTED or EXPIRED), and the hold is CONSUMED: the pieces were sold.
     *
     * @throws IllegalStateException when this confirmation is not the one in flight
     */
    public void settle(UUID confirmationId, Instant arrivedAt, String receiptNumber) {
        if (!confirmationId.equals(settlingConfirmationId)) {
            throw new IllegalStateException("order " + id + " is not being settled with " + confirmationId);
        }
        transitionTo(OrderStatus.PAID);
        this.holdState = HoldState.CONSUMED;
        this.holdExtensionPending = false;
        this.paidConfirmationId = confirmationId;
        // A paid order carries no attempt, whoever's money paid it: an attempt still in flight at payment is the owner's to settle there.
        this.providerPaymentId = null;
        this.paidAt = arrivedAt;
        this.receiptNumber = receiptNumber;
        this.settlingConfirmationId = null;
    }

    /** Whether this is the claim the shopper already made (the same method and the same reference, in its one spelling). */
    public boolean hasClaim(ClaimMethod method, String normalisedReference) {
        return claimId != null && claimMethod == method && claimReference.equals(normalisedReference);
    }

    /**
     * The shopper says they paid: CREATED -> PAYMENT_SUBMITTED, with the claim kept beside it. The stretch of the hold is still to be confirmed
     * by inventory, so it is marked pending; {@link #holdExtended} clears it.
     *
     * @throws IllegalStateException when the order is not CREATED and held, or has already taken a claim
     */
    public void submitClaim(ClaimMethod method, String normalisedReference, Instant at) {
        if (status != OrderStatus.CREATED || holdState != HoldState.HELD) {
            throw new IllegalStateException("order " + id + " is not waiting for payment (" + status + ", hold " + holdState + ")");
        }
        if (claimId != null) {
            throw new IllegalStateException("order " + id + " has already taken a payment claim");
        }
        transitionTo(OrderStatus.PAYMENT_SUBMITTED);
        this.claimId = UUID.randomUUID();
        this.claimMethod = method;
        this.claimReference = normalisedReference;
        this.claimSubmittedAt = at;
        this.holdExtensionPending = true;
    }

    /** Inventory stretched the hold to {@code newExpiry}: the extension is no longer pending (and the hold is never shortened). */
    public void holdExtended(Instant newExpiry) {
        // To the microsecond the database keeps, so the time an event reports is exactly the time the row holds.
        Instant stored = newExpiry.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (holdExpiresAt == null || stored.isAfter(holdExpiresAt)) {
            this.holdExpiresAt = stored;
        }
        this.holdExtensionPending = false;
        this.updatedAt = Instant.now();
    }

    /** The hold ran out (inventory says so) before the shopper paid: EXPIRED, and the hold is given back. */
    public void expire() {
        transitionTo(OrderStatus.EXPIRED);
        this.holdState = HoldState.RELEASED;
        this.holdExtensionPending = false;
    }

    public OrderItem addItem(UUID variantId, String title, int quantity, BigDecimal unitPrice) {
        Ngwee.require(unitPrice, "unitPrice");
        OrderItem item = new OrderItem(this, variantId, title, quantity, unitPrice);
        items.add(item);
        recomputeTotals();
        return item;
    }

    /**
     * A line as Phase 7 sells it: at the LIST price, with the promotion's discount per piece beside it (never subtracted from the price), the
     * product inventory needs, and the id the line's box will be sold under.
     */
    public OrderItem addItem(UUID variantId, UUID productId, String title, int quantity, BigDecimal unitPrice,
                             BigDecimal discountPerPiece, UUID promotionId) {
        Ngwee.require(unitPrice, "unitPrice");
        Ngwee.require(discountPerPiece, "discountPerPiece");
        if (discountPerPiece.signum() < 0 || discountPerPiece.compareTo(unitPrice) > 0) {
            throw new IllegalArgumentException("discountPerPiece must be between 0 and the unit price");
        }
        OrderItem item = new OrderItem(this, variantId, productId, title, quantity, unitPrice,
                discountPerPiece.setScale(2, RoundingMode.UNNECESSARY), discountPerPiece.signum() == 0 ? null : promotionId);
        items.add(item);
        recomputeTotals();
        return item;
    }

    /**
     * Keeps who the parcel is for and where it goes, as they were at placement. Called once, on a new delivered order, before it is saved.
     *
     * @throws IllegalStateException for a pickup (it delivers to nobody) or an order that already has them
     */
    public void recordDelivery(String recipientName, String recipientPhone, String line1, String line2, String city, String state, String postalCode,
                               String country, String optionName, String townName) {
        if (isSelfCollect()) {
            throw new IllegalStateException("a pickup is delivered to nobody");
        }
        if (this.recipientName != null) {
            throw new IllegalStateException("order " + id + " already has its delivery details");
        }
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.addressLine1 = line1;
        this.addressLine2 = line2;
        this.addressCity = city;
        this.addressState = state;
        this.addressPostalCode = postalCode;
        this.addressCountry = country;
        this.deliveryOptionName = optionName;
        this.deliveryTownName = townName;
    }

    /**
     * Takes the order's place in the queue for the pieces: mints the reservation id (BEFORE inventory is asked, so a repeat names the same
     * one) and remembers the fingerprint of the quote and the caller's Idempotency-Key. The order is PENDING until inventory has answered.
     */
    public void startHold(String quoteDigest, String idempotencyKey) {
        if (holdState != null) {
            throw new IllegalStateException("order " + id + " already has a hold");
        }
        this.reservationId = UUID.randomUUID();
        this.holdState = HoldState.PENDING;
        this.quoteDigest = quoteDigest;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Inventory holds the pieces until {@code expiresAt}; {@code boxes} says which box it set aside for each line (by the line's box-line id).
     */
    public void markHeld(Instant heldAt, Instant expiresAt, java.util.Map<UUID, UUID> boxes) {
        if (holdState != HoldState.PENDING) {
            throw new IllegalStateException("order " + id + " is not waiting for its hold (" + holdState + ")");
        }
        this.holdState = HoldState.HELD;
        this.heldAt = heldAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.holdExpiresAt = expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        items.stream().filter(i -> i.boxLineId() != null && boxes.containsKey(i.boxLineId()))
                .forEach(i -> i.holdBox(boxes.get(i.boxLineId())));
        this.updatedAt = Instant.now();
    }

    /**
     * A paid order that has not been dispatched is cancelled by its correction (Phase 12): PAID or FULFILLED to CANCELLED. The ONLY way there:
     * {@link #transitionTo} refuses the same edge, so nothing cancels a paid order without putting its stock back and recording what is owed.
     * The hold stays CONSUMED (the pieces were sold; the correction, not a release, is what returned them) and nothing of the sale is rewritten:
     * the receipt number, the day the money arrived, the lines and the totals stay as they were sold.
     *
     * @throws IllegalStateException the order is not PAID or FULFILLED
     */
    public void cancelPaid() {
        if (status != OrderStatus.PAID && status != OrderStatus.FULFILLED) {
            throw new IllegalStateException("order " + id + " is " + status + ": only a paid order that has not been dispatched is cancelled this way");
        }
        this.status = OrderStatus.CANCELLED;
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
        if (next == OrderStatus.CANCELLED && (status == OrderStatus.PAID || status == OrderStatus.FULFILLED)) {
            // The edge exists for the paid cancellation alone (cancelPaid): undoing a sale puts its stock back and records what is owed.
            throw new IllegalStateException("Cannot cancel order " + id + ": it is paid, and a paid order is cancelled only through its paid cancellation");
        }
        // The status graph cannot see the delivery option: a pickup is handed over, never shipped, and only a pickup is handed over (Phase 8).
        if (status == OrderStatus.FULFILLED && next == OrderStatus.SHIPPED && isSelfCollect()) {
            throw new IllegalStateException("Cannot ship order " + id + ": its shopper collects it, so it is handed over, never shipped");
        }
        if (status == OrderStatus.FULFILLED && next == OrderStatus.DELIVERED && !isSelfCollect()) {
            throw new IllegalStateException("Cannot deliver order " + id + " from FULFILLED: only a pickup is handed over without being shipped first");
        }
        this.status = next;
        if ((next == OrderStatus.CANCELLED || next == OrderStatus.EXPIRED) && (holdState == HoldState.HELD || holdState == HoldState.PENDING)) {
            // An order that ends unpaid gives its hold back (inventory lets go of the reservation the ending event names).
            this.holdState = HoldState.RELEASED;
            this.holdExtensionPending = false;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * subtotal + shipping - discount: the same formula as the shared
     * {@code Quote.total()}, which OrderTotalsTest holds the two to. An
     * order built by the old constructor carries no shipping or discount
     * (unknown, not zero) and is not marked, so its total stays its subtotal.
     */
    /**
     * Makes this order's delivery free (Phase 7, design 3.4): delivery becomes K0.00, the total falls by exactly what delivery would have cost, and who, why,
     * when and how much are kept. Only an order still waiting for a payment reference, whose pieces are held and on which no money is being applied: a total
     * the shopper may already have paid cannot move.
     *
     * @throws IllegalStateException the order is not in a state in which its total may change, or has no delivery to waive, or was already waived; the
     *                               message says which, and the service turns it into the shopper-facing refusal
     */
    public void waiveDelivery(String by, String reason, Instant at) {
        if (status != OrderStatus.CREATED || holdState != HoldState.HELD) {
            throw new IllegalStateException("not-allowed");
        }
        if (settlingConfirmationId != null) {
            throw new IllegalStateException("being-applied");
        }
        if (providerPaymentId != null) {
            // The amount ZynlePay was asked for is the total as it stood: lowering it now would make the money that comes the wrong amount.
            throw new IllegalStateException("provider-payment");
        }
        if (deliveryWaivedAmount != null) {
            throw new IllegalStateException("already-waived");
        }
        if (!totalsAuthoritative || shippingAmount == null || shippingAmount.signum() == 0) {
            throw new IllegalStateException("nothing-to-waive");
        }
        this.deliveryWaivedAmount = shippingAmount;
        this.deliveryWaivedBy = by;
        this.deliveryWaiverReason = reason;
        this.deliveryWaivedAt = at;
        this.shippingAmount = new BigDecimal("0.00");
        recomputeTotals();
        this.updatedAt = at;
    }

    private void recomputeTotals() {
        BigDecimal sub = items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.subtotal = sub;
        if (totalsAuthoritative) {
            // The discount is the sum of what each piece's promotion took off, worked out per piece and never split across an order.
            this.discountAmount = items.stream()
                    .map(i -> i.discountPerPiece().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.UNNECESSARY);
        }
        this.total = totalsAuthoritative
                ? sub.add(shippingAmount).subtract(discountAmount).setScale(2, RoundingMode.UNNECESSARY)
                : sub;
    }
}
