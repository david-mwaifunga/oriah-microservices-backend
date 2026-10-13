package com.oriah.shipping.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.shipping.config.ShippingTariff;
import com.oriah.shipping.model.Zone;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingServiceTest {

    /**
     * A test-local rate card, deliberately NOT the shipped one. These tests are
     * about BEHAVIOUR -- zone classification and the free-over-threshold rule
     * -- so they must not fail when the business changes a tariff. What ships
     * in application.yaml is pinned separately by TariffBindingTest, which is
     * where a typo in the yaml surfaces.
     */
    private static final ShippingTariff TARIFF = new ShippingTariff(
            "ZMW",
            new ShippingTariff.ZoneTariff(new BigDecimal("125.00"), new BigDecimal("300.00"),
                    new BigDecimal("3750.00")),
            new ShippingTariff.ZoneTariff(new BigDecimal("375.00"), new BigDecimal("875.00"),
                    new BigDecimal("6250.00")),
            new ShippingTariff.ZoneTariff(new BigDecimal("1125.00"), new BigDecimal("2125.00"),
                    new BigDecimal("12500.00")));

    private final ShippingService service = new ShippingService(TARIFF);

    @Test
    @DisplayName("NG -> DOMESTIC zone with Local Courier options")
    void domesticZone() {
        var resp = service.quote("NG", new BigDecimal("50.00"), "ZMW");
        assertThat(resp.zone()).isEqualTo(Zone.DOMESTIC);
        assertThat(resp.options()).hasSize(2);
        assertThat(resp.options().get(0).carrier()).isEqualTo("Local Courier");
    }

    @Test
    @DisplayName("ZA -> REGIONAL")
    void regionalZone() {
        var resp = service.quote("ZA", new BigDecimal("100"), "ZMW");
        assertThat(resp.zone()).isEqualTo(Zone.REGIONAL);
    }

    @Test
    @DisplayName("US -> INTERNATIONAL")
    void internationalZone() {
        var resp = service.quote("US", new BigDecimal("100"), "ZMW");
        assertThat(resp.zone()).isEqualTo(Zone.INTERNATIONAL);
    }

    @Test
    @DisplayName("a subtotal at the zone threshold makes domestic Standard shipping free")
    void freeStandardOverThreshold() {
        // 3750.00 is this test card's domestic threshold, not a magic number:
        // the rule under test is "at or above the threshold", and the figure
        // comes from the tariff rather than from the service.
        var resp = service.quote("NG", new BigDecimal("3750.00"), "ZMW");
        assertThat(resp.options().get(0).cost()).isEqualByComparingTo(BigDecimal.ZERO);
        // Express still costs money.
        assertThat(resp.options().get(1).cost()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("a subtotal one unit below the threshold is still charged")
    void justBelowTheThresholdStillCosts() {
        // The boundary, from the other side. >= and > are one character apart
        // and the difference is a free delivery on every order at exactly the
        // threshold.
        var resp = service.quote("NG", new BigDecimal("3749.99"), "ZMW");
        assertThat(resp.options().get(0).cost()).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("null country code defaults to INTERNATIONAL")
    void nullCountryIsInternational() {
        assertThat(Zone.classify(null)).isEqualTo(Zone.INTERNATIONAL);
    }
}
