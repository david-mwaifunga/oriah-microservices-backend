package com.oriah.catalog.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * How pricing behaves, under {@code oriah.catalog.pricing}.
 *
 * <p>Mutable, like {@link CatalogProperties}, because Spring Cloud Context rebinds a properties bean in place on a refresh.
 */
@ConfigurationProperties(prefix = "oriah.catalog.pricing")
@Validated
public class PricingProperties {

    /** The shop's currency. A price in any other is refused: lots are costed in it, and a price in another could not be compared. */
    @NotBlank
    private String currency = "ZMW";

    /** How far back a price may be dated: a paper tag changed this morning and keyed tonight is real, a fortnight ago is not a late key. */
    @NotNull
    private Duration backdateWindow = Duration.ofHours(72);

    /**
     * The target profit percentage (of the landed cost) behind the suggested price. No default: the client has not said the figure,
     * and a suggestion from an invented one would read as advice. Absent means no suggestion is shown unless the caller asks for one.
     */
    private BigDecimal targetProfitPercent;

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Duration getBackdateWindow() {
        return backdateWindow;
    }

    public void setBackdateWindow(Duration backdateWindow) {
        this.backdateWindow = backdateWindow;
    }

    public BigDecimal getTargetProfitPercent() {
        return targetProfitPercent;
    }

    public void setTargetProfitPercent(BigDecimal targetProfitPercent) {
        this.targetProfitPercent = targetProfitPercent;
    }
}
