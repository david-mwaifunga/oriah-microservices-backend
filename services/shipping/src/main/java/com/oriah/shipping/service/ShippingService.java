package com.oriah.shipping.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.oriah.shipping.config.ShippingTariff;
import com.oriah.shipping.dto.RateResponse;
import com.oriah.shipping.exception.OutsideDeliveryAreaException;
import com.oriah.shipping.model.ShippingOption;

import lombok.extern.slf4j.Slf4j;

/**
 * What delivery Oriah offers, and at what price.
 *
 * <h2>Until Phase 7: Zambia only, self-collect only</h2>
 *
 * The old rate card (express, regional, international, a K125 "domestic"
 * standard and a K3,750 free-delivery threshold) was a USD list multiplied by
 * 25 that nobody chose, and its "domestic" zone was Nigeria -- a Lusaka
 * customer was charged the international K1,125. The owner prices delivery
 * per town (Phase 7), and chose self-collect only until then (2026-11-08). So
 * Zambia is offered exactly one option, {@link ShippingOption#SELF_COLLECT}
 * at K0, and any other country is refused (MVP_PLAN Phase 0, item 1).
 *
 * <h2>The currency is still checked</h2>
 *
 * Asked to quote in a currency this service does not publish, it refuses
 * (400). Converting needs an exchange rate, and a rate invented inside a
 * shipping calculator misprices every order silently.
 */
@Service
@Slf4j
public class ShippingService {

    /** The only country Oriah delivers to. */
    static final String ZAMBIA = "ZM";

    private final ShippingTariff tariff;

    public ShippingService(ShippingTariff tariff) {
        this.tariff = tariff;
    }

    /**
     * @param requestedCurrency what the caller's money is denominated in, or
     *                          null to accept this service's own currency
     * @param deliveryOption    null for every offered option, or one code to
     *                          quote only that option (empty when not offered)
     * @throws IllegalArgumentException     a currency this service does not quote in (400)
     * @throws OutsideDeliveryAreaException any country but Zambia (422)
     */
    public RateResponse quote(String countryCode, BigDecimal subtotal, String requestedCurrency,
                              String deliveryOption) {
        requireQuotableCurrency(requestedCurrency);
        String country = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        if (!ZAMBIA.equals(country)) {
            throw new OutsideDeliveryAreaException();
        }
        List<ShippingOption> offered = List.of(selfCollect()).stream()
                .filter(o -> deliveryOption == null || o.code().equals(deliveryOption))
                .toList();
        log.debug("quote country={} subtotal={} {} option={} offered={}",
                country, subtotal, tariff.currency(), deliveryOption, offered.size());
        return new RateResponse(country, offered);
    }

    /** The customer collects from Oriah, at no charge; nobody has promised a time. */
    private ShippingOption selfCollect() {
        return new ShippingOption(ShippingOption.SELF_COLLECT, "Oriah Jewelry", "Self-collect",
                new BigDecimal("0.00"), tariff.currency(), null, null);
    }

    private void requireQuotableCurrency(String requested) {
        if (requested == null || requested.isBlank()) {
            return;
        }
        // Forgiving about shape, strict about identity: the value arrives over
        // HTTP from another service, so case and padding are not worth
        // refusing a sale over -- a DIFFERENT currency is.
        if (!tariff.currency().equalsIgnoreCase(requested.trim())) {
            throw new IllegalArgumentException("Shipping rates are published in "
                    + tariff.currency() + "; cannot quote in " + requested.trim());
        }
    }
}