package com.oriah.shipping.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the service actually ships with, read from the real
 * {@code application.yaml}.
 *
 * <h2>Why a separate test from ShippingServiceTest</h2>
 *
 * That one uses a test-local rate card, because it is about behaviour -- zone
 * classification, the free-over-threshold rule -- and must not fail when the
 * business changes a tariff. Nothing was then left watching the shipped
 * numbers, and a rate card is exactly the kind of configuration where a
 * misplaced decimal point is invisible: {@code free-over: 375.00} instead of
 * {@code 3750.00} gives away free delivery on almost every order, and no test
 * about behaviour would notice.
 *
 * <h2>And the currency, which is the load-bearing one</h2>
 *
 * Every quote is denominated by {@code tariff.currency}, and checkout refuses
 * a quote whose currency differs from the bag's. So this value is what decides
 * whether a Kwacha bag can be shipped at all: get it wrong and either every
 * checkout is refused, or -- before that refusal existed -- a dollar cost was
 * added to a Kwacha total as though the two were the same unit.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class TariffBindingTest {

    @Autowired
    private ShippingTariff tariff;

    /**
     * A half-written rate card must stop the service, not bind nulls.
     *
     * <p>The constraints on ShippingTariff were INERT until this was written:
     * Spring Boot runs the JSR-303 validator over a @ConfigurationProperties
     * type only when the type carries @Validated, and it did not. The javadoc
     * claimed otherwise, so the claim was false and nothing checked it.
     *
     * <p>What that cost, concretely: an edit to config-repo/shipping.yaml that
     * leaves free-over empty binds null (config-repo wins key by key over
     * application.yaml), ShippingService.standard() then evaluates
     * subtotal.compareTo(null) -> NPE -> 500, checkout reads a 500 as an
     * outage, and every domestic order ships FREE. Silently, because that
     * degradation is by design.
     *
     * <p>An ApplicationContextRunner rather than a @SpringBootTest, following
     * ContactInfoBindingTest: the outcome under test is a context that REFUSES
     * to start, which cannot be written as a context that has to start.
     */
    @Test
    @DisplayName("a rate card with a missing figure refuses to start the service")
    void aPartialCardFailsTheContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(TariffHolder.class)
                .withPropertyValues(
                        "oriah.shipping.tariff.currency=ZMW",
                        "oriah.shipping.tariff.domestic.standard-cost=125.00",
                        "oriah.shipping.tariff.domestic.express-cost=300.00",
                        // free-over deliberately absent
                        "oriah.shipping.tariff.regional.standard-cost=375.00",
                        "oriah.shipping.tariff.regional.express-cost=875.00",
                        "oriah.shipping.tariff.regional.free-over=6250.00",
                        "oriah.shipping.tariff.international.standard-cost=1125.00",
                        "oriah.shipping.tariff.international.express-cost=2125.00",
                        "oriah.shipping.tariff.international.free-over=12500.00")
                .run(ctx -> assertThat(ctx)
                        .as("quoting from a partially bound money table is worse than "
                                + "not starting")
                        .hasFailed());
    }

    @Test
    @DisplayName("a currency that is not a 3-letter code refuses to start the service")
    void aMalformedCurrencyFailsTheContext() {
        // ZMWX would reach ShippingService.requireQuotableCurrency and refuse
        // every quote from checkout with a 400 -- which, before the companion
        // fix in CheckoutService, was swallowed as an outage and shipped
        // everything free.
        new ApplicationContextRunner()
                .withUserConfiguration(TariffHolder.class)
                .withPropertyValues(
                        "oriah.shipping.tariff.currency=ZMWX",
                        "oriah.shipping.tariff.domestic.standard-cost=125.00",
                        "oriah.shipping.tariff.domestic.express-cost=300.00",
                        "oriah.shipping.tariff.domestic.free-over=3750.00",
                        "oriah.shipping.tariff.regional.standard-cost=375.00",
                        "oriah.shipping.tariff.regional.express-cost=875.00",
                        "oriah.shipping.tariff.regional.free-over=6250.00",
                        "oriah.shipping.tariff.international.standard-cost=1125.00",
                        "oriah.shipping.tariff.international.express-cost=2125.00",
                        "oriah.shipping.tariff.international.free-over=12500.00")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("a complete card binds and starts")
    void aCompleteCardStarts() {
        // The other direction, so the two tests above cannot pass by the
        // holder being broken in some unrelated way.
        new ApplicationContextRunner()
                .withUserConfiguration(TariffHolder.class)
                .withPropertyValues(
                        "oriah.shipping.tariff.currency=ZMW",
                        "oriah.shipping.tariff.domestic.standard-cost=125.00",
                        "oriah.shipping.tariff.domestic.express-cost=300.00",
                        "oriah.shipping.tariff.domestic.free-over=3750.00",
                        "oriah.shipping.tariff.regional.standard-cost=375.00",
                        "oriah.shipping.tariff.regional.express-cost=875.00",
                        "oriah.shipping.tariff.regional.free-over=6250.00",
                        "oriah.shipping.tariff.international.standard-cost=1125.00",
                        "oriah.shipping.tariff.international.express-cost=2125.00",
                        "oriah.shipping.tariff.international.free-over=12500.00")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @EnableConfigurationProperties(ShippingTariff.class)
    static class TariffHolder {
    }

    @Test
    @DisplayName("the shipped rate card is denominated in the brand currency")
    void theShippedCardIsInKwacha() {
        assertThat(tariff.currency())
                .as("the brand prices in Kwacha; a USD card here means checkout "
                        + "refuses every Kwacha bag")
                .isEqualTo("ZMW");
    }

    @Test
    @DisplayName("the shipped tariffs are the documented values, decimal points included")
    void theShippedTariffsAreTheDocumentedValues() {
        // These are the old USD card multiplied by 25, which is the basis
        // recorded in application.yaml. The test exists to catch a typo, so it
        // repeats the numbers deliberately rather than deriving them.
        assertThat(tariff.domestic().standardCost()).isEqualByComparingTo("125.00");
        assertThat(tariff.domestic().expressCost()).isEqualByComparingTo("300.00");
        assertThat(tariff.domestic().freeOver()).isEqualByComparingTo("3750.00");

        assertThat(tariff.regional().standardCost()).isEqualByComparingTo("375.00");
        assertThat(tariff.regional().expressCost()).isEqualByComparingTo("875.00");
        assertThat(tariff.regional().freeOver()).isEqualByComparingTo("6250.00");

        assertThat(tariff.international().standardCost()).isEqualByComparingTo("1125.00");
        assertThat(tariff.international().expressCost()).isEqualByComparingTo("2125.00");
        assertThat(tariff.international().freeOver()).isEqualByComparingTo("12500.00");
    }

    @Test
    @DisplayName("no zone gives away free shipping at a trivial subtotal")
    void noZoneIsAccidentallyAlwaysFree() {
        // The shape of the misplaced-decimal mistake, caught independently of
        // the exact figures above: a threshold near a single item's price
        // means free delivery on effectively every order, which is a revenue
        // leak nobody would see in a test about behaviour.
        assertThat(tariff.domestic().freeOver()).isGreaterThan(java.math.BigDecimal.valueOf(1000));
        assertThat(tariff.regional().freeOver()).isGreaterThan(java.math.BigDecimal.valueOf(1000));
        assertThat(tariff.international().freeOver())
                .isGreaterThan(java.math.BigDecimal.valueOf(1000));
    }

    @Test
    @DisplayName("express never undercuts standard, in any zone")
    void expressIsNeverCheaperThanStandard() {
        // A swapped pair in the yaml -- standard and express transposed -- is
        // another invisible edit: both numbers are plausible, and the only
        // symptom is shoppers paying less for next-day than for a week.
        assertThat(tariff.domestic().expressCost())
                .isGreaterThan(tariff.domestic().standardCost());
        assertThat(tariff.regional().expressCost())
                .isGreaterThan(tariff.regional().standardCost());
        assertThat(tariff.international().expressCost())
                .isGreaterThan(tariff.international().standardCost());
    }
}
