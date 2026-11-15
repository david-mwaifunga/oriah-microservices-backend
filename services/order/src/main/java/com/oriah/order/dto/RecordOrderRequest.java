package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.money.NgweeAmount;
import com.oriah.order.model.SaleKind;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What staff record when a caller with no account buys by phone, by chat or on paper (Phase 9). The person says WHO is buying, WHAT, how it goes and (for a
 * paper sale) WHEN; the server prices every line and the delivery itself (the person types no price, no discount, no currency and no total: Q12), and the
 * person confirms the total they read to the caller ({@code expectedTotal}) so a price that moved between the quote and the recording is never charged unseen.
 *
 * <h2>Nothing a price can be typed into</h2>
 *
 * {@code total}, {@code discount}, {@code shippingAmount} and a line's {@code unitPrice} and {@code discountPerPiece} exist only so a request that carries one is
 * REFUSED (400) rather than silently ignored: an amount sent by a person can only be a mistake. They are hidden from the contract.
 *
 * @param kind            {@code LIVE_PHONE} (happening now; dated now) or {@code LATE_PAPER} (written on paper, typed in later; dated {@code occurredAt})
 * @param caller          who is buying: a name always, a phone for any delivery
 * @param lines           what is bought; the box defaults to the product's own unless {@code boxVariantId} names another
 * @param delivery        how it goes: a pickup, the shop's published town price, or a delivery agreed by phone
 * @param recipient       who the parcel is for, when not the caller (a delivery only)
 * @param address         where it goes (a delivery only)
 * @param occurredAt      a late paper sale's true date; refused on a live sale
 * @param backdateReason  why a late paper sale is recorded late, 5 to 200 characters; required for it
 * @param slipReference   the paper slip's number, when it has one
 * @param expectedTotal   the total the person read to the caller; a different total is refused (409 {@code total-differs}) and nothing is held
 */
public record RecordOrderRequest(
        @NotNull SaleKind kind,
        @NotNull @Valid Caller caller,
        @NotEmpty @Size(min = 1, max = 100) @Valid List<Line> lines,
        @NotNull @Valid Delivery delivery,
        @Valid Recipient recipient,
        @Valid Address address,
        Instant occurredAt,
        @Size(max = 200) String backdateReason,
        @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 ./#-]*$", message = "a slip number is letters, digits, space and . / # -") String slipReference,
        @NotNull(message = "expectedTotal is required: the total you read to the caller") @NgweeAmount BigDecimal expectedTotal,
        @Null(message = "a total cannot be typed: the server works it out") @NgweeAmount @Schema(hidden = true) BigDecimal total,
        @Null(message = "a discount cannot be typed: promotions apply by themselves") @NgweeAmount @Schema(hidden = true) BigDecimal discount,
        @Null(message = "a shipping amount cannot be typed: an agreed delivery goes in delivery.agreedAmount") @NgweeAmount @Schema(hidden = true) BigDecimal shippingAmount) {

    /** The delivery modes: no delivery, the shop's published price for a town, or an amount agreed by phone or WhatsApp. */
    public enum DeliveryMode {
        PICKUP, PUBLISHED, AGREED
    }

    /** Who is buying. */
    public record Caller(@NotBlank @Size(min = 1, max = 120) String name, @Size(max = 40) String phone) {
    }

    /** One line: the piece, how many, and optionally the box (the product's default when absent). */
    public record Line(@NotNull UUID variantId,
                       @Min(1) @Max(99) int quantity,
                       UUID boxVariantId,
                       @Null(message = "a price cannot be typed: the server prices every line") @NgweeAmount @Schema(hidden = true) BigDecimal unitPrice,
                       @Null(message = "a discount cannot be typed: promotions apply by themselves") @NgweeAmount @Schema(hidden = true) BigDecimal discountPerPiece) {
    }

    /**
     * How it goes. {@code PICKUP} takes nothing more; {@code PUBLISHED} and {@code AGREED} name an option and a town (from the places list) and who pays the
     * courier; only {@code AGREED} carries an amount ({@code agreedAmount}: K0.00 or absent when the recipient pays the courier) and, when it differs from the
     * shop's own quote, {@code agreedReason}.
     */
    public record Delivery(@NotNull DeliveryMode mode,
                           @Size(max = 64) @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$", message = "must be a delivery option code such as YANGO") String optionCode,
                           UUID townId,
                           @Pattern(regexp = "^(ORIAH_COLLECTS|RECIPIENT_PAYS)$", message = "must be ORIAH_COLLECTS or RECIPIENT_PAYS") String payer,
                           @NgweeAmount BigDecimal agreedAmount,
                           @Size(max = 200) String agreedReason) {
    }

    public record Recipient(@Size(max = 120) String name, @Size(max = 40) String phone) {
    }

    public record Address(@Size(max = 255) String line1, @Size(max = 255) String line2, @Size(max = 120) String city, @Size(max = 120) String state,
                          @Size(max = 20) String postalCode, @Pattern(regexp = "^[A-Z]{2}$", message = "must be a two-letter country code such as ZM") String country) {
    }
}
