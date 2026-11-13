package com.oriah.payment.confirmation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of staff-confirmed money ({@code oriah.payment.*}).
 *
 * @param currency       the one currency the shop takes (ZMW): a confirmation names it or takes it
 * @param backdateWindow how far back anyone who may confirm may date money (72 hours); further back needs BACKDATE_SALE and a reason
 */
@ConfigurationProperties(prefix = "oriah.payment")
public record ConfirmationSettings(
        @DefaultValue("ZMW") String currency,
        @DefaultValue("PT72H") Duration backdateWindow) {
}
