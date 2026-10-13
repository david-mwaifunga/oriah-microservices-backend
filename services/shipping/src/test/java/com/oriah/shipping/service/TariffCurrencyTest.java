package com.oriah.shipping.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.shipping.config.ShippingTariff;
import com.oriah.shipping.model.Zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A rate table is denominated, and this service will not pretend otherwise.
 *
 * <h2>What was wrong, and it was not just four string literals</h2>
 *
 * Every option was minted with the literal {@code "USD"}, and the thresholds
 * and costs -- free over 150, standard 5.00, express 12.00 -- were USD
 * magnitudes compiled into the switch. The brand prices in Kwacha. Relabelling
 * those same numbers ZMW would make free shipping start at about six dollars,
 * so every order would ship free, and a domestic delivery would cost about
 * twenty cents. The currency of a price is not a display concern: it changes
 * what the number MEANS.
 *
 * <p>Worse, nothing checked. Checkout added a shipping cost to the bag total
 * without looking at the quote's currency, so a USD cost went straight into a
 * ZMW total as though the two were the same unit.
 *
 * <h2>Refusing beats converting</h2>
 *
 * Asked for a currency it has no table for, this service refuses. Converting
 * would require an exchange rate, and an invented rate inside a shipping
 * calculator is a worse answer than "I cannot quote that": one is a visible
 * failure, the other is a quiet mispricing on every order.
 *
 * <p>A request that names NO currency is still quoted, in the table's own
 * currency. Checkout always names one; keeping the field optional means this
 * is an additive change to shipping's published contract rather than a
 * breaking one.
 */
class TariffCurrencyTest {

    /** A table in Kwacha, with deliberately distinctive numbers. */
    private static final ShippingTariff ZMW_TABLE = new ShippingTariff(
            "ZMW",
            new ShippingTariff.ZoneTariff(new BigDecimal("125.00"), new BigDecimal("300.00"),
                    new BigDecimal("3750.00")),
            new ShippingTariff.ZoneTariff(new BigDecimal("375.00"), new BigDecimal("875.00"),
                    new BigDecimal("6250.00")),
            new ShippingTariff.ZoneTariff(new BigDecimal("1125.00"), new BigDecimal("2125.00"),
                    new BigDecimal("12500.00")));

    private final ShippingService service = new ShippingService(ZMW_TABLE);

    @Test
    @DisplayName("every option carries the table's currency, not a hardcoded USD")
    void optionsCarryTheTableCurrency() {
        var response = service.quote("NG", new BigDecimal("100.00"), "ZMW");

        assertThat(response.options())
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.currency()).isEqualTo("ZMW"));
    }

    @Test
    @DisplayName("the costs and the free-shipping threshold come from the table")
    void theNumbersComeFromTheTable() {
        // Below the threshold: the configured standard cost applies.
        var dear = service.quote("NG", new BigDecimal("100.00"), "ZMW");
        assertThat(dear.zone()).isEqualTo(Zone.DOMESTIC);
        assertThat(dear.options().get(0).cost()).isEqualByComparingTo("125.00");
        assertThat(dear.options().get(1).cost()).isEqualByComparingTo("300.00");

        // At the threshold: standard becomes free, express does not.
        var free = service.quote("NG", new BigDecimal("3750.00"), "ZMW");
        assertThat(free.options().get(0).cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(free.options().get(1).cost())
                .as("free standard shipping is not free express shipping")
                .isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("a currency the table is not written in is refused, not converted")
    void aForeignCurrencyIsRefused() {
        assertThatThrownBy(() -> service.quote("NG", new BigDecimal("100.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ZMW");
    }

    @Test
    @DisplayName("a request naming no currency is quoted in the table's currency")
    void anAbsentCurrencyIsQuotedInTheTableCurrency() {
        // Keeps the contract change additive: shipping's existing callers do
        // not have to send the field at once.
        var response = service.quote("NG", new BigDecimal("100.00"), null);

        assertThat(response.options().get(0).currency()).isEqualTo("ZMW");
        assertThat(response.options().get(0).cost()).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("the currency match ignores case and padding, because a client sent it")
    void theCurrencyMatchIsForgivingAboutShape() {
        assertThat(service.quote("NG", new BigDecimal("100.00"), " zmw ").options())
                .isNotEmpty();
    }

    @Test
    @DisplayName("each zone has its own tariff, so one table change cannot move all three")
    void eachZoneIsPricedSeparately() {
        // NG is DOMESTIC, ZA is REGIONAL, anything unlisted is INTERNATIONAL
        // (Zone.classify). Worth noting while reading this file: the home
        // market is Nigeria while the brand prices in Kwacha, which is an
        // open question for the business, not something this test settles.
        assertThat(service.quote("ZA", new BigDecimal("100.00"), "ZMW")
                .options().get(0).cost()).isEqualByComparingTo("375.00");
        assertThat(service.quote("FR", new BigDecimal("100.00"), "ZMW")
                .options().get(0).cost()).isEqualByComparingTo("1125.00");
    }
}
