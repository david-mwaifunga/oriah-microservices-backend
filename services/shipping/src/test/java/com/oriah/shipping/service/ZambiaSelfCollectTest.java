package com.oriah.shipping.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.oriah.shipping.config.ShippingTariff;
import com.oriah.shipping.exception.OutsideDeliveryAreaException;
import com.oriah.shipping.model.ShippingOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Until Phase 7 prices delivery per town, Oriah offers one thing: collect
 * the piece yourself, at no charge, in Zambia (MVP_PLAN Phase 0, item 1;
 * the owner, 2026-11-08: self-collect only until Phase 7).
 *
 * <p>Why this replaces the old card: it was a USD list multiplied by 25 that
 * nobody chose, its "domestic" zone was Nigeria, and a Lusaka customer was
 * charged the international K1,125. Oriah does not sell abroad, so any other
 * country is refused -- never quoted, and never charged zero.
 */
class ZambiaSelfCollectTest {

    private final ShippingService service = new ShippingService(TariffFixtures.kwacha());

    @Test
    @DisplayName("Zambia is offered exactly one option: self-collect, at no charge, in Kwacha")
    void zambiaIsOfferedSelfCollectAtNoCharge() {
        var options = service.quote("ZM", new BigDecimal("200.00"), "ZMW", null).options();

        assertThat(options).hasSize(1);
        ShippingOption only = options.get(0);
        assertThat(only.code()).isEqualTo("SELF_COLLECT");
        assertThat(only.cost()).isEqualByComparingTo("0.00");
        assertThat(only.currency()).isEqualTo("ZMW");
        // Nobody has promised a collection time, so none is invented.
        assertThat(only.etaDaysMin()).isNull();
        assertThat(only.etaDaysMax()).isNull();
    }

    @Test
    @DisplayName("the country code is read without regard to case or padding")
    void lowerCaseZambiaIsZambia() {
        assertThat(service.quote(" zm ", new BigDecimal("200.00"), "ZMW", null).options())
                .extracting(ShippingOption::code)
                .containsExactly("SELF_COLLECT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NG", "US", "ZA", "GH", "GB"})
    @DisplayName("every other country is refused, in words the shopper can act on")
    void everyCountryButZambiaIsRefused(String country) {
        assertThatThrownBy(() -> service.quote(country, new BigDecimal("200.00"), "ZMW", null))
                .isInstanceOf(OutsideDeliveryAreaException.class)
                .hasMessage("We deliver within Zambia only.");
    }

    @Test
    @DisplayName("a named option is quoted alone; one that is not offered gives an empty list")
    void aNamedOptionIsQuotedAlone() {
        assertThat(service.quote("ZM", new BigDecimal("200.00"), "ZMW", "SELF_COLLECT").options())
                .extracting(ShippingOption::code)
                .containsExactly("SELF_COLLECT");
        assertThat(service.quote("ZM", new BigDecimal("200.00"), "ZMW", "OWN_DELIVERY").options())
                .isEmpty();
    }

    @Test
    @DisplayName("a foreign currency is still refused first, as a bad request")
    void aForeignCurrencyIsStillABadRequest() {
        assertThatThrownBy(() -> service.quote("ZM", new BigDecimal("200.00"), "USD", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ZMW");
    }

    /** One place builds the tariff, so the reshape changes one line. */
    static final class TariffFixtures {
        static ShippingTariff kwacha() {
            return new ShippingTariff("ZMW");
        }
    }
}
