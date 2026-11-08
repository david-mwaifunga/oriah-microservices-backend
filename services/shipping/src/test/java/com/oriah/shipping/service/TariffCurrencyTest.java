package com.oriah.shipping.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.shipping.config.ShippingTariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A price is denominated, and this service will not pretend otherwise.
 *
 * <p>Every option used to be minted with the literal {@code "USD"}, and
 * checkout added a shipping cost to the bag total without looking at the
 * quote's currency. The currency of a price is not a display concern: it
 * changes what the number MEANS.
 *
 * <p>Asked for a currency it does not publish, this service refuses.
 * Converting would require an exchange rate, and an invented rate inside a
 * shipping calculator is a quiet mispricing on every order; a refusal is
 * visible. A request that names NO currency is still quoted, in this
 * service's own currency, which keeps the field an additive contract change.
 *
 * <p>Rewritten for Phase 0 item 1: the zone card these tests once priced is
 * gone (self-collect only, Zambia only -- see ZambiaSelfCollectTest), so the
 * two tests that pinned its figures went with it. The currency rules stay.
 */
class TariffCurrencyTest {

    private final ShippingService service = new ShippingService(ZambiaSelfCollectTest.TariffFixtures.kwacha());

    @Test
    @DisplayName("every option carries the published currency, not a hardcoded USD")
    void optionsCarryTheTableCurrency() {
        var response = service.quote("ZM", new BigDecimal("100.00"), "ZMW", null);

        assertThat(response.options())
                .hasSize(1)
                .allSatisfy(o -> assertThat(o.currency()).isEqualTo("ZMW"));
    }

    @Test
    @DisplayName("a currency this service does not publish is refused, not converted")
    void aForeignCurrencyIsRefused() {
        assertThatThrownBy(() -> service.quote("ZM", new BigDecimal("100.00"), "USD", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ZMW");
    }

    @Test
    @DisplayName("a request naming no currency is quoted in the published currency")
    void anAbsentCurrencyIsQuotedInTheTableCurrency() {
        var response = service.quote("ZM", new BigDecimal("100.00"), null, null);

        assertThat(response.options()).hasSize(1);
        assertThat(response.options().get(0).currency()).isEqualTo("ZMW");
        assertThat(response.options().get(0).cost()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the currency match ignores case and padding, because a client sent it")
    void theCurrencyMatchIsForgivingAboutShape() {
        assertThat(service.quote("ZM", new BigDecimal("100.00"), " zmw ", null).options())
                .hasSize(1);
    }
}