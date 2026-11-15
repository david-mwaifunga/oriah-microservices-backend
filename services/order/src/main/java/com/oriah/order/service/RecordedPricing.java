package com.oriah.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.order.client.CatalogAsOfClient;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.dto.RecordOrderRequest;
import com.oriah.order.dto.RecordOrderRequest.DeliveryMode;
import com.oriah.order.exception.BagNotPurchasableException;
import com.oriah.order.exception.QuoteChangedException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.exception.TotalTooLargeException;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.model.SaleKind;
import com.oriah.order.service.OrderPricingService.Ask;
import com.oriah.order.service.OrderPricingService.Goods;
import com.oriah.order.service.OrderPricingService.PricedLine;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteLine;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * Prices a sale staff record (Phase 9): every line from catalog's STAFF price read at the instant of the sale, and the delivery as the person said it goes.
 * The person types no price, no discount, no currency and no total; this is where each of them comes from.
 *
 * <h2>Priced at the sale's own instant</h2>
 *
 * A live sale is priced as of now and a late paper sale as of its typed true date, whatever its age (a promotion that ended at ten o'clock still applies to a
 * sale made at ten to ten and keyed at twenty past): the kind is chosen by the person, never guessed from the date. The read ignores whether the piece is
 * published on the website (a piece on the shelf but not on the site can still be sold by phone), and a piece with no price at that instant is refused, never
 * priced at zero and never at today's price. It runs on the request thread with the PERSON'S token and outside any transaction.
 *
 * <h2>Delivery</h2>
 *
 * <ul>
 *   <li>{@code PICKUP}: K0.00, nothing delivered;</li>
 *   <li>{@code PUBLISHED}: the shop's own price for the town and option, asked of shipping (a live sale only: a published price is today's);</li>
 *   <li>{@code AGREED}: an amount agreed by phone ({@link AgreedDelivery}), for a town and option from shipping's places list, with a reason whenever it
 *       differs from the shop's own quote for that town (higher or lower; a quote that does not exist is nothing to differ from).</li>
 * </ul>
 */
@Service
@Slf4j
public class RecordedPricing {

    private final CatalogAsOfClient catalog;
    private final OrderPricingService goods;
    private final ShippingQuotes quotes;
    private final ShippingClient shipping;
    private final BigDecimal maxCharge;

    public RecordedPricing(CatalogAsOfClient catalog, OrderPricingService goods, ShippingQuotes quotes, ShippingClient shipping,
                           @Value("${oriah.sales.max-fare:5000.00}") BigDecimal maxCharge) {
        this.catalog = catalog;
        this.goods = goods;
        this.quotes = quotes;
        this.shipping = shipping;
        this.maxCharge = maxCharge;
    }

    /** How the delivery is charged, as the order will keep it. {@code agreedAmount} and {@code agreedReason} only for an agreed delivery. */
    public record DeliveryPriced(String source, String optionCode, String optionName, UUID townId, String townName, String payer, BigDecimal amount,
                                 BigDecimal agreedAmount, String agreedReason) {
    }

    /** The sale as priced: its lines, the delivery, and the shared quote whose total is the one formula everywhere. */
    public record Priced(List<PricedLine> lines, BigDecimal subtotal, BigDecimal discount, String currency, DeliveryPriced delivery, Quote quote) {

        public BigDecimal total() {
            return quote.total();
        }

        public boolean isPickup() {
            return Quote.SELF_COLLECT.equals(quote.deliveryOption());
        }
    }

    /**
     * @param at   the instant the sale is priced at: now for a live sale, the typed true date for a paper one
     * @param kind what the person chose
     * @throws SalesProblem 409 {@code no-price-at-date}, {@code late-sale-delivery-must-be-agreed}, {@code delivery-place-not-offered}, {@code town-not-offered};
     *                      400 {@code invalid-delivery}, {@code delivery-reason-required}
     * @throws BagNotPurchasableException a piece is unknown or is packaging (409)
     * @throws UpstreamUnavailableException catalog or shipping did not answer (503): nothing is saved
     */
    public Priced price(RecordOrderRequest req, Instant at, SaleKind kind) {
        List<UUID> distinct = req.lines().stream().map(RecordOrderRequest.Line::variantId).distinct().toList();
        CatalogAsOfClient.AsOfResponse answer = asOf(at, distinct);
        Map<UUID, CatalogAsOfClient.AsOfEntry> byVariant = answer == null || answer.results() == null ? Map.of()
                : answer.results().stream().filter(e -> e.variantId() != null).collect(Collectors.toMap(CatalogAsOfClient.AsOfEntry::variantId, e -> e, (a, b) -> a));
        refuseWhatCannotBePriced(req, byVariant, at);
        Map<UUID, CatalogPricingClient.Entry> entries = new HashMap<>();
        byVariant.forEach((id, e) -> entries.put(id, e.asPricingEntry()));
        Goods priced = goods.priceGoods(req.lines().stream().map(l -> new Ask(l.variantId(), l.quantity(), null)).toList(), entries, null);

        DeliveryPriced delivery = delivery(req, kind, priced);
        boolean pickup = delivery.optionCode() == null;
        Quote quote = new Quote(priced.lines().stream().map(l -> new QuoteLine(l.variantId(), l.quantity(), l.unitPrice(), l.discountPerPiece(), l.promotionId())).toList(),
                priced.currency(), pickup ? Quote.SELF_COLLECT : delivery.optionCode(), delivery.amount(), pickup ? null : delivery.townId(), pickup ? null : delivery.payer());
        if (quote.total().compareTo(OrderPricingService.MAX_TOTAL) > 0) {
            log.error("Refusing a recorded sale: total {} exceeds the {} the column holds", quote.total(), OrderPricingService.MAX_TOTAL);
            throw new TotalTooLargeException();
        }
        return new Priced(priced.lines(), priced.subtotal(), priced.discount(), priced.currency(), delivery, quote);
    }

    private CatalogAsOfClient.AsOfResponse asOf(Instant at, List<UUID> variantIds) {
        try {
            return catalog.priceAsOf(new CatalogAsOfClient.AsOfAsk(at, variantIds));
        } catch (FeignException e) {
            if (e.status() == 403 || e.status() == 401) {
                log.error("catalog REFUSED the as-of price read with {}: the person's token does not carry SALES_AGENT, or the path is wrong", e.status());
            } else {
                log.warn("The as-of price read was not answered: catalog status={}", e.status());
            }
            // Never a fallback to today's price and never a guess: nothing is saved and the person tries again.
            throw new UpstreamUnavailableException("catalog");
        }
    }

    private static void refuseWhatCannotBePriced(RecordOrderRequest req, Map<UUID, CatalogAsOfClient.AsOfEntry> byVariant, Instant at) {
        List<UUID> unknown = new ArrayList<>();
        List<UUID> noPrice = new ArrayList<>();
        for (RecordOrderRequest.Line line : req.lines()) {
            CatalogAsOfClient.AsOfEntry entry = byVariant.get(line.variantId());
            if (entry == null || !(CatalogAsOfClient.AsOfEntry.PRICED.equals(entry.status()) || CatalogAsOfClient.AsOfEntry.NO_PRICE_AT_DATE.equals(entry.status()))) {
                unknown.add(line.variantId());
            } else if (CatalogAsOfClient.AsOfEntry.NO_PRICE_AT_DATE.equals(entry.status())) {
                noPrice.add(line.variantId());
            }
        }
        if (!unknown.isEmpty()) {
            throw new BagNotPurchasableException(unknown);
        }
        if (!noPrice.isEmpty()) {
            throw new SalesProblem(HttpStatus.CONFLICT, "no-price-at-date",
                    "a piece had no price at " + at + " (it was first priced later, or never): it cannot be recorded at that date, because a sale is never priced at today's price. "
                            + "The owner (who may back-date a sale) can add a price from that date, with a reason, on the pricing screen; then record the sale again")
                    .with("variantIds", noPrice).with("at", at.toString());
        }
    }

    private DeliveryPriced delivery(RecordOrderRequest req, SaleKind kind, Goods priced) {
        RecordOrderRequest.Delivery d = req.delivery();
        BigDecimal afterDiscount = priced.subtotal().subtract(priced.discount());
        return switch (d.mode()) {
            case PICKUP -> {
                if (d.optionCode() != null || d.townId() != null || d.payer() != null || d.agreedAmount() != null || d.agreedReason() != null) {
                    throw invalid("a pickup takes no delivery option, town, payer or amount");
                }
                yield new DeliveryPriced("PUBLISHED", null, null, null, null, null, BigDecimal.ZERO.setScale(2), null, null);
            }
            case PUBLISHED -> {
                if (kind == SaleKind.LATE_PAPER) {
                    throw new SalesProblem(HttpStatus.CONFLICT, "late-sale-delivery-must-be-agreed",
                            "a published delivery price is today's price: a sale made on another day is a pickup, or its delivery is agreed (the amount that was agreed then)");
                }
                requireDelivering(d);
                if (d.agreedAmount() != null || d.agreedReason() != null) {
                    throw invalid("an amount or a reason belongs to an agreed delivery, not to the shop's published price");
                }
                ShippingQuotes.Delivery quoted = publishedQuote(d, afterDiscount, priced.currency());
                if (quoted == null) {
                    throw new SalesProblem(HttpStatus.CONFLICT, "town-not-offered",
                            "the shop has no published price for this town and option: agree the delivery instead (delivery mode AGREED)");
                }
                yield new DeliveryPriced("PUBLISHED", d.optionCode(), quoted.optionName(), quoted.townId() != null ? quoted.townId() : d.townId(), quoted.townName(),
                        quoted.payer(), quoted.amount(), null, null);
            }
            case AGREED -> {
                requireDelivering(d);
                Place place = place(d);
                BigDecimal charge = AgreedDelivery.check(d.optionCode(), d.payer(), d.agreedAmount(), maxCharge);
                BigDecimal reference = null;
                if (kind == SaleKind.LIVE_PHONE) {
                    ShippingQuotes.Delivery quoted = publishedQuote(d, afterDiscount, priced.currency());
                    reference = quoted == null ? null : quoted.amount();
                }
                String reason = AgreedDelivery.reasonFor(charge, reference, d.agreedReason());
                yield new DeliveryPriced("AGREED", d.optionCode(), place.option().name(), d.townId(), place.town().name(), d.payer(), charge, charge, reason);
            }
        };
    }

    private static void requireDelivering(RecordOrderRequest.Delivery d) {
        if (d.optionCode() == null || d.optionCode().isBlank() || Quote.SELF_COLLECT.equals(d.optionCode())) {
            throw invalid("a delivery names its option (a pickup is recorded as a pickup)");
        }
        if (d.townId() == null) {
            throw invalid("a delivery names its town");
        }
    }

    /** The shop's own quote for this town, option and payer, or null when it has none (no published price: a town not offered, or a payer not offered). */
    private ShippingQuotes.Delivery publishedQuote(RecordOrderRequest.Delivery d, BigDecimal afterDiscount, String currency) {
        try {
            return quotes.quote(d.optionCode(), d.townId(), d.payer(), afterDiscount, currency);
        } catch (QuoteChangedException notOffered) {
            return null;
        }
    }

    /** Where Oriah can deliver and by what, with no price: the list a salesperson picks from (shipping's own, read with order's machine token). */
    public ShippingClient.Places places() {
        return placesList();
    }

    private ShippingClient.Places placesList() {
        try {
            ShippingClient.Places places = shipping.places();
            if (places == null) {
                throw new UpstreamUnavailableException("shipping");
            }
            return places;
        } catch (FeignException e) {
            log.warn("The list of delivery places was not answered: shipping status={}", e.status());
            throw new UpstreamUnavailableException("shipping");
        }
    }

    private record Place(ShippingClient.Places.Option option, ShippingClient.Places.Town town) {
    }

    private Place place(RecordOrderRequest.Delivery d) {
        ShippingClient.Places places = placesList();
        ShippingClient.Places.Town town = places.provinces() == null ? null : places.provinces().stream().filter(p -> p.towns() != null).flatMap(p -> p.towns().stream())
                .filter(t -> d.townId().equals(t.id())).findFirst().orElse(null);
        ShippingClient.Places.Option option = places.options() == null ? null
                : places.options().stream().filter(o -> d.optionCode().equals(o.code())).findFirst().orElse(null);
        if (town == null || option == null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "delivery-place-not-offered",
                    "this town or delivery option is not on the shop's list (or is switched off): the owner adds a town or an option before a delivery to it can be agreed");
        }
        return new Place(option, town);
    }

    private static SalesProblem invalid(String detail) {
        return new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-delivery", detail);
    }
}
