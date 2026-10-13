package com.oriah.shipping.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.oriah.shipping.config.ShippingTariff;
import com.oriah.shipping.dto.RateResponse;
import com.oriah.shipping.model.ShippingOption;
import com.oriah.shipping.model.Zone;

import lombok.extern.slf4j.Slf4j;

/**
 * Flat-rate calculator. Free standard shipping above a per-zone threshold (a
 * cheap promo encoded directly into the carrier). A real implementation would
 * call FedEx/DHL APIs and account for weight + dimensions.
 *
 * <h2>The rate card is configuration, and it is denominated</h2>
 *
 * Every cost, threshold and currency used to be compiled into the switch
 * below, with the literal {@code "USD"} on each option. The brand prices in
 * Kwacha, and a currency is not a label on a price -- it decides what the
 * number means. Relabelling the USD card would have started free shipping at
 * roughly six dollars and charged about twenty cents to deliver a ring. The
 * card now comes from {@link ShippingTariff}.
 *
 * <p>Asked to quote in a currency the card is not written in, this service
 * refuses. Converting needs an exchange rate, and a rate invented inside a
 * shipping calculator misprices every order silently, where a refusal is
 * visible on the first request.
 */
@Service
@Slf4j
public class ShippingService {

    private final ShippingTariff tariff;

    public ShippingService(ShippingTariff tariff) {
        this.tariff = tariff;
    }

    /**
     * @param requestedCurrency what the caller's money is denominated in, or
     *                          null to accept this service's own currency.
     *                          Optional so that adding it stayed an ADDITIVE
     *                          change to shipping's published contract; the
     *                          only caller (checkout) always sends it, and
     *                          checks the answer again on its side.
     * @throws IllegalArgumentException the caller asked for a currency this
     *                                  rate card is not written in -- 400, and
     *                                  deliberately not a silent conversion
     */
    public RateResponse quote(String countryCode, BigDecimal subtotal, String requestedCurrency) {
        requireQuotableCurrency(requestedCurrency);
        Zone zone = Zone.classify(countryCode);
        log.debug("quote country={} zone={} subtotal={} {}",
                countryCode, zone, subtotal, tariff.currency());
        return new RateResponse(countryCode.toUpperCase(), zone, optionsFor(zone, subtotal));
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

    private List<ShippingOption> optionsFor(Zone zone, BigDecimal subtotal) {
        ShippingTariff.ZoneTariff rates = switch (zone) {
            case DOMESTIC -> tariff.domestic();
            case REGIONAL -> tariff.regional();
            case INTERNATIONAL -> tariff.international();
        };
        return switch (zone) {
            case DOMESTIC -> List.of(
                    standard("Local Courier", "Standard", rates, 2, 4, subtotal),
                    express("Local Courier", "Express", rates, 1, 2)
            );
            case REGIONAL -> List.of(
                    standard("Pan-African", "Standard", rates, 5, 10, subtotal),
                    express("Pan-African", "Express", rates, 2, 4)
            );
            case INTERNATIONAL -> List.of(
                    standard("DHL", "International", rates, 7, 14, subtotal),
                    express("DHL", "Express International", rates, 3, 6)
            );
        };
    }

    /** Standard option, free when the subtotal reaches the zone's threshold. */
    private ShippingOption standard(String carrier, String service,
                                    ShippingTariff.ZoneTariff rates,
                                    int etaMin, int etaMax, BigDecimal subtotal) {
        BigDecimal cost = subtotal.compareTo(rates.freeOver()) >= 0
                ? BigDecimal.ZERO
                : rates.standardCost();
        return new ShippingOption(carrier, service, cost, tariff.currency(), etaMin, etaMax);
    }

    /**
     * Express is never free. A shopper paying for speed is buying something
     * the free-over-threshold promo does not cover.
     */
    private ShippingOption express(String carrier, String service,
                                   ShippingTariff.ZoneTariff rates, int etaMin, int etaMax) {
        return new ShippingOption(carrier, service, rates.expressCost(),
                tariff.currency(), etaMin, etaMax);
    }
}
