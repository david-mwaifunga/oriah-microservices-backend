package com.oriah.payment.confirmation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of staff-confirmed money ({@code oriah.payment.*}).
 *
 * @param currency the one currency the shop takes (ZMW): a confirmation names it or takes it. (How far back money may be dated is the sale's one setting,
 *                 {@code oriah.sales.backdate-window}, in {@link SalesSettings}.)
 */
@ConfigurationProperties(prefix = "oriah.payment")
public record ConfirmationSettings(
        @DefaultValue("ZMW") String currency) {
}
