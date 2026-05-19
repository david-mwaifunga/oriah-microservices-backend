package com.oriah.shipping.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.shipping.model.Zone;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingServiceTest {

    private final ShippingService service = new ShippingService();

    @Test
    @DisplayName("NG -> DOMESTIC zone with Local Courier options")
    void domesticZone() {
        var resp = service.quote("NG", new BigDecimal("50.00"));
        assertThat(resp.zone()).isEqualTo(Zone.DOMESTIC);
        assertThat(resp.options()).hasSize(2);
        assertThat(resp.options().get(0).carrier()).isEqualTo("Local Courier");
    }

    @Test
    @DisplayName("ZA -> REGIONAL")
    void regionalZone() {
        var resp = service.quote("ZA", new BigDecimal("100"));
        assertThat(resp.zone()).isEqualTo(Zone.REGIONAL);
    }

    @Test
    @DisplayName("US -> INTERNATIONAL")
    void internationalZone() {
        var resp = service.quote("US", new BigDecimal("100"));
        assertThat(resp.zone()).isEqualTo(Zone.INTERNATIONAL);
    }

    @Test
    @DisplayName("subtotal >= 150 USD makes domestic Standard shipping free")
    void freeStandardOverThreshold() {
        var resp = service.quote("NG", new BigDecimal("200.00"));
        assertThat(resp.options().get(0).cost()).isEqualByComparingTo(BigDecimal.ZERO);
        // Express still costs money.
        assertThat(resp.options().get(1).cost()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("null country code defaults to INTERNATIONAL")
    void nullCountryIsInternational() {
        assertThat(Zone.classify(null)).isEqualTo(Zone.INTERNATIONAL);
    }
}
