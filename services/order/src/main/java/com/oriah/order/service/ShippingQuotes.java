package com.oriah.order.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.oriah.money.Ngwee;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.exception.QuoteChangedException;
import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * What the delivery option the shopper chose costs, asked of shipping by order
 * itself (MVP_PLAN Phase 0, item 4.2). Never a figure the caller sends, and
 * never a quiet zero:
 *
 * <ul>
 *   <li>shipping does not answer, or answers 5xx -> 503: nothing is placed;</li>
 *   <li>shipping refuses our request (4xx), answers nothing, or answers with
 *       a cost that is not a whole, non-negative number of ngwee in the
 *       order's currency -> 500: our bug or bad data, never the shopper's 400;</li>
 *   <li>the option is not offered (any more) -> 409 pricing-changed: the
 *       shopper re-quotes and chooses again.</li>
 * </ul>
 *
 * <p>The destination is Zambia: until Phase 7 the only option is self-collect,
 * which happens in Zambia, and checkout has already refused an address abroad
 * before order is asked. From Phase 7 the option code names the town as well.
 */
@Service
@Slf4j
public class ShippingQuotes {

    static final String ZAMBIA = "ZM";

    private final ShippingClient shipping;

    public ShippingQuotes(ShippingClient shipping) {
        this.shipping = shipping;
    }

    public BigDecimal costOf(String option, BigDecimal subtotal, String currency) {
        ShippingClient.RateResponse rates;
        try {
            rates = shipping.rates(new ShippingClient.RateRequest(ZAMBIA, subtotal, currency, option));
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                log.error("shipping REFUSED order's quote request for option={} with {}", option, e.status(), e);
                throw new UpstreamRejectedException("shipping refused order's quote request with " + e.status());
            }
            log.warn("No delivery price for option={}: shipping status={}", option, e.status());
            throw new UpstreamUnavailableException("shipping");
        }
        if (rates == null || rates.options() == null) {
            throw new UpstreamRejectedException("shipping answered with no options");
        }
        Optional<ShippingClient.ShippingOption> offered = rates.options().stream()
                .filter(o -> option.equals(o.code()))
                .findFirst();
        if (offered.isEmpty()) {
            log.info("409 delivery option {} is not offered", option);
            throw new QuoteChangedException();
        }
        ShippingClient.ShippingOption chosen = offered.get();
        if (chosen.currency() == null || !chosen.currency().equalsIgnoreCase(currency)) {
            log.error("shipping quoted {} in {} for a {} order -- refusing rather than adding one "
                    + "currency to another", option, chosen.currency(), currency);
            throw new UpstreamRejectedException("shipping quoted in " + chosen.currency());
        }
        BigDecimal cost = chosen.cost();
        if (cost == null || cost.signum() < 0 || !Ngwee.isWholeNgwee(cost)) {
            log.error("shipping quoted an unusable cost {} for {}", cost, option);
            throw new UpstreamRejectedException("shipping quoted an unusable cost");
        }
        return cost;
    }
}