package com.oriah.shipping.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The published rate card, and the currency it is written in.
 *
 * <h2>Why this exists as configuration</h2>
 *
 * These numbers used to be compiled into {@code ShippingService}'s switch
 * alongside the literal {@code "USD"}: free over 150, standard 5.00, express
 * 12.00, and so on per zone. They are business tariffs, not logic, and the
 * brand's currency is configurable by decision -- so the rate card has to be
 * configurable with it. A currency is not a label on these numbers; it decides
 * what they mean. Relabelling the USD card as Kwacha would start free shipping
 * at about six dollars and charge about twenty cents to deliver a ring.
 *
 * <h2>The defaults are a mechanical conversion, and should be replaced</h2>
 *
 * The shipped defaults are the previous USD card multiplied by 25, which is
 * roughly the Kwacha rate and is recorded here so the basis is auditable
 * rather than mysterious. They are NOT a commercial decision: real tariffs
 * depend on carrier contracts nobody here has. Overriding them is a yaml edit
 * in {@code application.yaml} or {@code config-repo/shipping.yaml}, which is
 * the point of moving them out of the switch.
 *
 * <h2>Validated, with defaults present in application.yaml</h2>
 *
 * {@code @Valid} and the constraints below fail the context on a half-written
 * rate card, which is the correct failure for money configuration -- quoting
 * from a partially bound table is worse than not starting. That cuts the other
 * way too: a service whose only source for these values were the Config Server
 * would refuse to start when it was down, which is the mistake this project
 * already made once with contact-info. So application.yaml carries a complete
 * local card and the Config Server overrides it key by key.
 */
@ConfigurationProperties(prefix = "oriah.shipping.tariff")
// @Validated, without which the constraints below are INERT. Spring Boot runs
// the JSR-303 validator over a @ConfigurationProperties type only when the type
// carries this annotation, so the javadoc above claimed a startup failure that
// could not happen: a half-written rate card bound nulls, and
// ShippingService.standard() then threw an NPE on freeOver() -- rendered as a
// 500, which checkout treats as an outage, which ships every order free.
// Pinned by TariffBindingTest.
@Validated
public record ShippingTariff(

        /**
         * The ISO 4217 code every quote from this service is denominated in.
         * A request naming a different one is refused rather than converted:
         * an exchange rate invented inside a shipping calculator misprices
         * every order quietly, where a refusal is visible immediately.
         */
        @NotNull @Size(min = 3, max = 3) String currency,

        @NotNull @Valid ZoneTariff domestic,
        @NotNull @Valid ZoneTariff regional,
        @NotNull @Valid ZoneTariff international
) {

    /**
     * One zone's prices.
     *
     * @param freeOver the subtotal at or above which STANDARD shipping is
     *                 free. Express is never free: a shopper paying for speed
     *                 is buying something the threshold promo does not cover.
     */
    public record ZoneTariff(
            @NotNull @DecimalMin("0.00") BigDecimal standardCost,
            @NotNull @DecimalMin("0.00") BigDecimal expressCost,
            @NotNull @DecimalMin("0.00") BigDecimal freeOver
    ) {
    }
}
