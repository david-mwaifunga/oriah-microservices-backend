package com.oriah.order.dto;

import com.oriah.money.NgweeAmount;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Caller submits what to buy.
 *
 * <h2>The bounds exist so the DATABASE never decides</h2>
 *
 * {@code V1__create_orders.sql} carries {@code CHECK (quantity > 0)},
 * {@code CHECK (unit_price >= 0)} and {@code title VARCHAR(255)}, and this
 * record carried nothing but {@code @NotNull}. So every malformed line reached
 * Postgres, violated a constraint or overflowed a column, and came back as
 * {@code 500 An unexpected error occurred} -- verified live for
 * {@code quantity: -5}, {@code quantity: 0}, {@code quantity: 2000000000} and
 * a 5,000-character title.
 *
 * <p>That is wrong twice over: the caller is told nothing it can act on, and
 * an ordinary bad request is recorded as the server fault an operator pages
 * on. With the bounds here, the CHECK constraints go back to being backstops
 * that never fire -- and if one ever fires again, a 500 is then the honest
 * answer, because it means validation has a hole.
 *
 * <h2>No money in this request is trusted</h2>
 *
 * order prices every line from catalog and the delivery from shipping, and
 * stores those figures. {@code unitPrice} is a CLAIM: one that disagrees with
 * catalog refuses the placement, and it can never lower what is charged. The
 * {@code title} is overwritten with catalog's. And {@link #quoteDigest()} binds
 * the whole quote the shopper saw -- every line's quantity and price, the
 * delivery option and its amount, the currency -- so anything that moved since
 * preview is refused rather than charged unseen.
 *
 * <p>cart has already dropped {@code unitPrice} and {@code title} from its own
 * request. Removing them here too is a contract change for checkout and is not
 * part of this one; until then their bounds keep a malformed line a 400.
 */
@AddressUnlessSelfCollect
@TownUnlessSelfCollect
@ContactUnlessSelfCollect
public record PlaceOrderRequest(
        /**
         * Where to deliver. Not needed when the shopper collects
         * ({@link AddressUnlessSelfCollect}); required for every other option.
         */
        UUID shippingAddressId,

        /**
         * Required, and exactly three upper-case letters.
         *
         * <p>It was {@code @Size(min = 3, max = 3)} and nullable, and
         * {@code Order}'s constructor turned a missing one into {@code "USD"}
         * -- on a platform priced in Kwacha. Verified live: a request omitting
         * currency returned 201 with {@code "currency": "USD"}. It was the
         * last hardcoded USD in live code; cart and catalog hold
         * {@code DEFAULT_CURRENCY = "ZMW"}, shipping's is configuration, and
         * payment's identical default was removed in the same pass.
         *
         * <p>Upper-case specifically, because
         * {@code PaymentIntentVerifier} compares this order's currency to the
         * intent's with {@code equals}: a lower-case order could never be
         * settled by an upper-case intent.
         */
        @NotBlank
        @Size(min = 3, max = 3)
        @Pattern(regexp = "^[A-Z]{3}$",
                message = "must be a three-letter upper-case currency code")
        String currency,

        /**
         * @param items capped at {@link #MAX_LINES}. catalog's pricing
         *              endpoint enforces {@code @Size(max = 100)} on a batch,
         *              which is why cart caps a bag at 100 -- and an order is
         *              the record of a bag, so an order that could not have
         *              been a bag should not be constructible. Once order
         *              prices from catalog itself, the batch limit applies to
         *              it directly.
         */
        @NotEmpty @Size(min = 1, max = MAX_LINES) @Valid List<LineItem> items,

        /**
         * The delivery option the shopper chose: {@code SELF_COLLECT} now, a
         * code per town and option from Phase 7. order asks shipping what it
         * costs -- the caller never names the amount.
         */
        @NotBlank
        @Size(min = 1, max = 64)
        @Pattern(regexp = Quote.DELIVERY_OPTION_PATTERN,
                message = "must be a delivery option code such as SELF_COLLECT")
        String deliveryOption,

        /**
         * The town the delivery is priced for (Phase 7: delivery is priced per town). Required for every option but self-collect, which
         * needs none ({@link TownUnlessSelfCollect}). Shipping validates it against what is published and switched on.
         */
        UUID deliveryTownId,

        /**
         * Who pays the courier: {@code ORIAH_COLLECTS} (it is in the total) or {@code RECIPIENT_PAYS} (paid on arrival: K0 on this order).
         * Absent takes the one the town's price offers. Shipping says which it resolved, and the order records that.
         */
        @Pattern(regexp = "^(ORIAH_COLLECTS|RECIPIENT_PAYS)$", message = "must be ORIAH_COLLECTS or RECIPIENT_PAYS")
        String payer,

        /**
         * The digest of the quote the shopper saw at preview -- quote, then
         * commit (MVP_PLAN Phase 0, item 4.4). order prices everything itself
         * and refuses with 409 pricing-changed, saving nothing, when its own
         * digest differs: a basket edited in another tab, a price or delivery
         * price changed since, is never charged unseen.
         */
        @NotBlank
        @Size(min = 64, max = 64)
        @Pattern(regexp = QuoteDigest.PATTERN, message = "must be 64 lower-case hex characters")
        String quoteDigest,

        /**
         * Who the parcel is for (Phase 8): required for every delivered option and refused for self-collect ({@link ContactUnlessSelfCollect}). Kept on
         * the order in one spelling, so the packer and the driver have someone to ask for and a number to ring.
         */
        @Size(max = com.oriah.quote.DeliveryContact.NAME_MAX)
        String recipientName,

        /** The recipient's phone number as typed (097 123 4567, +260 97 123 4567 ...); kept as +260971234567. */
        @Size(max = 40)
        String recipientPhone,

        /**
         * Never accepted (Phase 9). What delivery costs is the shop's published town price, worked out by shipping and order; an amount sent by a customer
         * can only be a mistake or an attempt, so it is refused rather than silently ignored. An agreed delivery enters an order only through staff.
         * Hidden from the contract: it exists so the request can be refused.
         */
        @jakarta.validation.constraints.Null(message = "a delivery amount cannot be sent: the price comes from the shop")
        @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
        @com.oriah.money.NgweeAmount
        java.math.BigDecimal deliveryAmount,

        /**
         * Never accepted (Phase 9). What delivery costs is the shop's published town price, worked out by shipping and order; an amount sent by a customer
         * can only be a mistake or an attempt, so it is refused rather than silently ignored. An agreed delivery enters an order only through staff.
         * Hidden from the contract: it exists so the request can be refused.
         */
        @jakarta.validation.constraints.Null(message = "a delivery amount cannot be sent: the price comes from the shop")
        @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
        @com.oriah.money.NgweeAmount
        java.math.BigDecimal shippingAmount,

        /**
         * Never accepted (Phase 9). What delivery costs is the shop's published town price, worked out by shipping and order; an amount sent by a customer
         * can only be a mistake or an attempt, so it is refused rather than silently ignored. An agreed delivery enters an order only through staff.
         * Hidden from the contract: it exists so the request can be refused.
         */
        @jakarta.validation.constraints.Null(message = "a delivery amount cannot be sent: the price comes from the shop")
        @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
        @com.oriah.money.NgweeAmount
        java.math.BigDecimal total,
        /** Never accepted (Phase 9): a promotion's discount is worked out by the shop per piece; a discount sent by a customer is refused, never ignored. */
        @jakarta.validation.constraints.Null(message = "a discount cannot be sent: promotions apply by themselves")
        @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
        @com.oriah.money.NgweeAmount
        java.math.BigDecimal discount
) {

    /** A request with no recipient: a pickup. */
    public PlaceOrderRequest(UUID shippingAddressId, String currency, List<LineItem> items, String deliveryOption, UUID deliveryTownId, String payer,
                             String quoteDigest) {
        this(shippingAddressId, currency, items, deliveryOption, deliveryTownId, payer, quoteDigest, null, null, null, null, null, null);
    }

    /** A request with a recipient, as every caller but the refusal tests builds it. */
    public PlaceOrderRequest(UUID shippingAddressId, String currency, List<LineItem> items, String deliveryOption, UUID deliveryTownId, String payer,
                             String quoteDigest, String recipientName, String recipientPhone) {
        this(shippingAddressId, currency, items, deliveryOption, deliveryTownId, payer, quoteDigest, recipientName, recipientPhone, null, null, null, null);
    }

    /**
     * <h2>Why every @Size carries an explicit {@code min}</h2>
     *
     * springdoc derives {@code minItems}/{@code minLength} from {@code @Size}
     * and NOT from {@code @NotEmpty}/{@code @NotBlank}. So
     * {@code @NotEmpty @Size(max = 100)} published
     * {@code minItems: 0} -- replacing the {@code minItems: 1} the bare
     * {@code @NotEmpty} used to produce, and telling every generated client
     * that an empty order is acceptable while the server still refused it. The
     * same happened to {@code title}: {@code @NotBlank @Size(max = 255)} gave
     * {@code minLength: 0}.
     *
     * <p>A looser published contract beside a stricter server is worse than no
     * contract, because a client trusts it. The OpenAPI diff gate caught both,
     * the second time in this phase it has caught exactly this -- payment's
     * currency lost {@code maxLength} the same way.
     */

    /**
     * Mirrors {@code Cart.MAX_LINES}. Declared here rather than imported
     * because cart is a separate module with its own schema -- the shared
     * reason is catalog's 100-id pricing batch, not a dependency between the
     * two services.
     */
    public static final int MAX_LINES = 100;

    /** Mirrors {@code Cart.MAX_QUANTITY_PER_LINE}, for the same reason. */
    public static final int MAX_QUANTITY_PER_LINE = 99;

    public record LineItem(
            @NotNull UUID variantId,

            /**
             * Bounded to the column it is stored in. {@code VARCHAR(255)}, and
             * a 256-character title was a 500 straight from Postgres. cart met
             * the same wall and removed the field; this bound holds until
             * order does the same.
             */
            @NotBlank @Size(min = 1, max = 255) String title,

            /**
             * @param quantity at least 1 -- {@code CHECK (quantity > 0)} made
             *                 a zero a 500 -- and at most
             *                 {@link #MAX_QUANTITY_PER_LINE}. The ceiling is
             *                 not cosmetic: 2,000,000,000 x 100.00 overflows
             *                 {@code NUMERIC(12,2)} when the line total is
             *                 computed, which was also a 500.
             */
            @NotNull @Min(1) @Max(MAX_QUANTITY_PER_LINE) Integer quantity,

            /**
             * @param unitPrice floor only. See the class javadoc: a floor is
             *                  not authority, and the client still gets to
             *                  name a price above it. {@code CHECK
             *                  (unit_price >= 0)} allows zero, so zero is
             *                  allowed here too -- tightening it to a positive
             *                  value would be a guess at a rule that belongs
             *                  with the pricing authority work, and a zero
             *                  order is visible rather than silent.
             */
            @NotNull @DecimalMin("0.00") @NgweeAmount java.math.BigDecimal unitPrice
    ) {}
}
