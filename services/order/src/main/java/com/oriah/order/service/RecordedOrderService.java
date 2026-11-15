package com.oriah.order.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.order.dto.RecordOrderRequest;
import com.oriah.order.dto.RecordOrderRequest.DeliveryMode;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.HoldState;
import com.oriah.order.model.Order;
import com.oriah.order.model.RecordedSale;
import com.oriah.order.model.SaleKind;
import com.oriah.quote.DeliveryContact;

import lombok.extern.slf4j.Slf4j;

/**
 * Records a sale taken by phone, chat or paper for a caller with no account (Phase 9). Staff (SALES_AGENT) say who is buying, what, how it goes and, for a paper
 * sale, when; the server dates it, prices it and holds the pieces like any order; payment is the existing staff confirmation (Phase 7).
 *
 * <h2>Deliberately NOT @Transactional</h2>
 *
 * Pricing asks catalog and shipping and the hold asks inventory, and none of them may run inside a database transaction (the sale's rule, see
 * {@link OrderService}). So this method only orders the steps, and the two transactions live on {@link RecordedOrderWriter}:
 *
 * <ol>
 *   <li>a short look for the Idempotency-Key, BEFORE anything else: a repeat after a lost reply is answered as it was recorded, whatever has moved since;</li>
 *   <li>the date ({@link SaleDating}) and the price ({@link RecordedPricing}), outside any transaction, with the person's own token;</li>
 *   <li>the total the person read to the caller is checked against the one the server worked out (nothing is held on a difference);</li>
 *   <li>the order written PENDING under one lock, then the hold taken and the order announced ({@link OrderService#finishPlacement}, the same second half a
 *       shopper's placement uses).</li>
 * </ol>
 *
 * <p>A hold lasts ten minutes (CLIENT_DECISIONS: one kind of hold); money confirmed after it lapsed still pays the order if its pieces are free, and is held
 * for the caller (never lost) if they were sold meanwhile.
 */
@Service
@Slf4j
public class RecordedOrderService {

    private final RecordedOrderWriter writer;
    private final RecordedPricing pricing;
    private final OrderService orders;
    private final Clock clock;
    private final Duration backdateWindow;

    public RecordedOrderService(RecordedOrderWriter writer, RecordedPricing pricing, OrderService orders, Clock clock,
                                @Value("${oriah.sales.backdate-window:PT72H}") Duration backdateWindow) {
        this.writer = writer;
        this.pricing = pricing;
        this.orders = orders;
        this.clock = clock;
        this.backdateWindow = backdateWindow;
    }

    /** Who is recording: {@code user:<keycloak subject>}, and whether they may back-date a sale (BACKDATE_SALE). */
    public record Who(String actor, boolean mayBackdate) {
    }

    /** What recording came to: the order, and whether THIS call made it (201) or the same request had made it before (200). */
    public record Recorded(Order order, boolean created) {
    }

    /** What a preview came to: the sale as it would be recorded, nothing written and nothing held. */
    public record Previewed(RecordedPricing.Priced priced, Instant occurredAt, SaleKind kind) {
    }

    /**
     * @param key the Idempotency-Key, 1 to 128 characters
     * @throws SalesProblem 400 for a contact that cannot be read, a delivery with no address or phone, an invalid date or delivery; 409 {@code idempotency-key-reused},
     *                      {@code total-differs}, {@code zero-total-not-supported}, {@code no-price-at-date}, {@code insufficient-stock} and the delivery refusals
     * @throws org.springframework.security.access.AccessDeniedException a paper sale older than the window by someone who may not back-date a sale (403)
     */
    public Recorded record(RecordOrderRequest req, String key, Who who) {
        Contacts contacts = contacts(req);
        String hash = RequestHash.of(req, contacts.callerName(), contacts.callerPhone(), contacts.recipientName(), contacts.recipientPhone());

        // A repeat is answered first, as it was recorded: before the date rule, the price or any call, so a request sent again a day later is still the same one.
        Optional<Order> prior = writer.findByKey(key);
        if (prior.isPresent()) {
            RecordedOrderWriter.requireSameRequest(prior.get(), hash);
            return new Recorded(prior.get().holdState() == HoldState.PENDING ? orders.finishPlacement(prior.get(), who.actor()) : prior.get(), false);
        }

        Instant now = clock.instant();
        SaleDating.Dated dated = SaleDating.check(req.kind(), req.occurredAt(), now, backdateWindow, who.mayBackdate(), req.backdateReason());
        // ONE instant is the sale's date and its price's date: a sale typed a little ahead of this clock is dated and priced now, so it can never straddle midnight
        // (priced on one Lusaka day, counted on the next).
        Instant when = saleInstant(dated.occurredAt(), now);
        RecordedPricing.Priced priced = pricing.price(req, when, req.kind());
        requireTheTotalTheyRead(req, priced);

        RecordedSale sale = new RecordedSale(who.actor(), contacts.callerName(), contacts.callerPhone(), req.kind(), when, dated.reason(), req.slipReference(),
                hash, priced.delivery().agreedAmount(), priced.delivery().agreedReason());
        OrderWriter.DeliveryDetails details = priced.isPickup() ? null : deliveryDetails(req, contacts, priced);
        RecordedOrderWriter.Begun begun = writer.begin(key, sale, priced, req, details);
        if (!begun.created()) {
            // Another request with this key won the race while this one was pricing: it is the same order.
            return new Recorded(begun.order().holdState() == HoldState.PENDING ? orders.finishPlacement(begun.order(), who.actor()) : begun.order(), false);
        }
        return new Recorded(orders.finishPlacement(begun.order(), who.actor()), true);
    }

    /** The sale as it would be recorded, with nothing written, held or kept: the total to read to the caller. The caller is not needed (a price-only call). */
    public Previewed preview(RecordOrderRequest req, Who who) {
        Instant now = clock.instant();
        SaleDating.Dated dated = SaleDating.check(req.kind(), req.occurredAt(), now, backdateWindow, who.mayBackdate(), req.backdateReason());
        Instant when = saleInstant(dated.occurredAt(), now);
        return new Previewed(pricing.price(req, when, req.kind()), when, req.kind());
    }

    /**
     * The instant a sale is dated and priced at. A paper sale may be typed a few minutes ahead of this clock (the date rule allows for a clock running fast), but catalog
     * refuses a moment more than 30 seconds in the future and nothing has happened yet: it is dated and priced now.
     */
    private static Instant saleInstant(Instant occurredAt, Instant now) {
        return occurredAt.isAfter(now) ? now : occurredAt;
    }

    private static void requireTheTotalTheyRead(RecordOrderRequest req, RecordedPricing.Priced priced) {
        BigDecimal total = priced.total();
        if (total.signum() == 0) {
            throw new SalesProblem(HttpStatus.CONFLICT, "zero-total-not-supported",
                    "a sale that comes to K0.00 cannot be recorded: payment takes no confirmation below K0.01. A gift goes through the stock give-away screen");
        }
        if (req.expectedTotal().compareTo(total) != 0) {
            throw new SalesProblem(HttpStatus.CONFLICT, "total-differs",
                    "the total you read to the caller (K" + req.expectedTotal().toPlainString() + ") is not the one the shop works out (K" + total.toPlainString()
                            + "): read them the new total, and record it again with it")
                    .with("total", total);
        }
    }

    private record Contacts(String callerName, String callerPhone, String recipientName, String recipientPhone) {
    }

    /** The caller, and who the parcel is for, read by the one shared rule (a phone is read the way Zambians type it, and kept as +260971234567). */
    private static Contacts contacts(RecordOrderRequest req) {
        boolean delivered = req.delivery().mode() != DeliveryMode.PICKUP;
        String callerName = read(() -> DeliveryContact.name(req.caller().name()), "caller.name");
        String phoneTyped = req.caller().phone();
        String callerPhone = phoneTyped == null || phoneTyped.isBlank() ? null : read(() -> DeliveryContact.phone(phoneTyped), "caller.phone");
        if (!delivered) {
            if (req.recipient() != null || req.address() != null) {
                throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-delivery", "a pickup is collected by the caller: it takes no recipient and no address");
            }
            return new Contacts(callerName, callerPhone, null, null);
        }
        if (callerPhone == null) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-contact", "caller.phone is required for a delivery: the driver needs a number to ring");
        }
        RecordOrderRequest.Recipient r = req.recipient();
        String recipientName = r == null || r.name() == null || r.name().isBlank() ? callerName : read(() -> DeliveryContact.name(r.name()), "recipient.name");
        String recipientPhone = r == null || r.phone() == null || r.phone().isBlank() ? callerPhone : read(() -> DeliveryContact.phone(r.phone()), "recipient.phone");
        return new Contacts(callerName, callerPhone, recipientName, recipientPhone);
    }

    private static String read(java.util.function.Supplier<String> rule, String field) {
        try {
            return rule.get();
        } catch (IllegalArgumentException unreadable) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-contact", field + ": " + unreadable.getMessage());
        }
    }

    private static OrderWriter.DeliveryDetails deliveryDetails(RecordOrderRequest req, Contacts contacts, RecordedPricing.Priced priced) {
        RecordOrderRequest.Address a = req.address();
        if (a == null || a.line1() == null || a.line1().isBlank()) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-delivery", "a delivery needs the address line the driver goes to (address.line1): a street, a plot or a landmark");
        }
        String city = a.city() == null || a.city().isBlank() ? priced.delivery().townName() : a.city().strip();
        String country = a.country() == null || a.country().isBlank() ? "ZM" : a.country();
        return new OrderWriter.DeliveryDetails(contacts.recipientName(), contacts.recipientPhone(), a.line1().strip(), blankToNull(a.line2()), city, blankToNull(a.state()),
                blankToNull(a.postalCode()), country);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
