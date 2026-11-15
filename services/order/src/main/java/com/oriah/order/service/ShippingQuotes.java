package com.oriah.order.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oriah.money.Ngwee;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.exception.QuoteChangedException;
import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * What delivery to the town the shopper chose costs, asked of shipping by order itself (Phase 7; design FINAL E4: shipping is the single
 * place that resolves the delivery amount). Never a figure the caller sends, and never a quiet zero:
 *
 * <ul>
 *   <li>shipping does not answer, or answers 5xx -> 503: nothing is placed;</li>
 *   <li>shipping refuses our request as malformed (400), answers nothing, or answers with a cost that is not a whole, non-negative number
 *       of ngwee in the order's currency -> 500: our bug or bad data, never the shopper's 400;</li>
 *   <li>the town or option is not offered (any more), or the payer is not one it offers (409), or the town is unknown (404) -> 409
 *       pricing-changed: the shopper re-quotes and chooses again.</li>
 * </ul>
 */
@Service
@Slf4j
public class ShippingQuotes {

    private final ShippingClient shipping;

    public ShippingQuotes(ShippingClient shipping) {
        this.shipping = shipping;
    }

    /** What shipping priced: the amount that goes on the order, and the town and payer it resolved (null for self-collect). */
    public record Delivery(BigDecimal amount, UUID townId, String payer, String townName, String optionName) {

        /** A delivery that names neither its town nor its option. */
        public Delivery(BigDecimal amount, UUID townId, String payer) {
            this(amount, townId, payer, null, null);
        }
    }

    /**
     * @param goodsAfterDiscount what a free-delivery threshold is judged on
     */
    public Delivery quote(String option, UUID townId, String payer, BigDecimal goodsAfterDiscount, String currency) {
        ShippingClient.QuoteReply reply;
        try {
            reply = shipping.quote(new ShippingClient.QuoteAsk(townId, option, payer, goodsAfterDiscount, currency));
        } catch (FeignException e) {
            if (e.status() == 409 || e.status() == 404) {
                log.info("409 delivery option={} town={} payer={} is not offered: shipping said {}", option, townId, payer, e.status());
                throw new QuoteChangedException();
            }
            if (e.status() >= 400 && e.status() < 500) {
                log.error("shipping REFUSED order's quote request for option={} with {}", option, e.status(), e);
                throw new UpstreamRejectedException("shipping refused order's quote request with " + e.status());
            }
            log.warn("No delivery price for option={}: shipping status={}", option, e.status());
            throw new UpstreamUnavailableException("shipping");
        }
        if (reply == null || reply.amount() == null) {
            throw new UpstreamRejectedException("shipping answered with no amount");
        }
        if (reply.currency() == null || !reply.currency().equalsIgnoreCase(currency)) {
            log.error("shipping quoted {} in {} for a {} order -- refusing rather than adding one currency to another", option, reply.currency(), currency);
            throw new UpstreamRejectedException("shipping quoted in " + reply.currency());
        }
        BigDecimal cost = reply.amount();
        if (cost.signum() < 0 || !Ngwee.isWholeNgwee(cost)) {
            log.error("shipping quoted an unusable cost {} for {}", cost, option);
            throw new UpstreamRejectedException("shipping quoted an unusable cost");
        }
        String resolvedPayer = reply.payer();
        boolean knownPayer = resolvedPayer == null || resolvedPayer.equals("ORIAH_COLLECTS") || resolvedPayer.equals("RECIPIENT_PAYS");
        if (!knownPayer || ("RECIPIENT_PAYS".equals(resolvedPayer) && cost.signum() != 0)) {
            // The order records what is OWED to Oriah: a courier the recipient pays on arrival is never on it.
            log.error("shipping quoted payer {} with cost {} for {}: unusable", resolvedPayer, cost, option);
            throw new UpstreamRejectedException("shipping quoted an unusable payer");
        }
        if ("OWN_DELIVERY".equals(option) && "RECIPIENT_PAYS".equals(resolvedPayer)) {
            log.error("shipping quoted Oriah's own delivery as paid by the recipient: refusing (Q27: own delivery is income, there is no driver cash float)");
            throw new UpstreamRejectedException("shipping quoted own delivery as recipient-paid");
        }
        return new Delivery(cost, reply.townId(), resolvedPayer, reply.townName(), reply.optionName());
    }
}
