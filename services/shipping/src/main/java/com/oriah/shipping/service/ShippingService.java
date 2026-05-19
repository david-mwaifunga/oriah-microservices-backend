package com.oriah.shipping.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.oriah.shipping.dto.RateResponse;
import com.oriah.shipping.model.ShippingOption;
import com.oriah.shipping.model.Zone;

import lombok.extern.slf4j.Slf4j;

/**
 * Flat-rate calculator. Free shipping above a per-zone threshold (a
 * cheap promo encoded directly into the carrier). A real implementation
 * would call FedEx/DHL APIs and account for weight + dimensions.
 */
@Service
@Slf4j
public class ShippingService {

    public RateResponse quote(String countryCode, BigDecimal subtotal) {
        Zone zone = Zone.classify(countryCode);
        log.debug("quote country={} zone={} subtotal={}", countryCode, zone, subtotal);
        return new RateResponse(countryCode.toUpperCase(), zone, optionsFor(zone, subtotal));
    }

    private List<ShippingOption> optionsFor(Zone zone, BigDecimal subtotal) {
        return switch (zone) {
            case DOMESTIC -> List.of(
                    standard("Local Courier", "Standard", "5.00", 2, 4, subtotal, BigDecimal.valueOf(150)),
                    new ShippingOption("Local Courier", "Express", new BigDecimal("12.00"), "USD", 1, 2)
            );
            case REGIONAL -> List.of(
                    standard("Pan-African", "Standard", "15.00", 5, 10, subtotal, BigDecimal.valueOf(250)),
                    new ShippingOption("Pan-African", "Express", new BigDecimal("35.00"), "USD", 2, 4)
            );
            case INTERNATIONAL -> List.of(
                    standard("DHL", "International", "45.00", 7, 14, subtotal, BigDecimal.valueOf(500)),
                    new ShippingOption("DHL", "Express International", new BigDecimal("85.00"), "USD", 3, 6)
            );
        };
    }

    /**
     * Standard option, free when subtotal >= freeOverThreshold.
     */
    private ShippingOption standard(String carrier, String service, String baseCost,
                                    int etaMin, int etaMax, BigDecimal subtotal, BigDecimal freeOverThreshold) {
        BigDecimal cost = subtotal.compareTo(freeOverThreshold) >= 0 ? BigDecimal.ZERO : new BigDecimal(baseCost);
        return new ShippingOption(carrier, service, cost, "USD", etaMin, etaMax);
    }
}
