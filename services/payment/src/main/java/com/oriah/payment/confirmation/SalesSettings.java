package com.oriah.payment.confirmation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the sale as a whole ({@code oriah.sales.*}), read by payment for the one thing it watches: money confirmed that order has not
 * answered.
 *
 * @param settlementStuckAfter how long a confirmation may wait for order's answer before it is called stuck: listed for staff, announced again by
 *                             the watcher, and logged as an error
 */
@ConfigurationProperties(prefix = "oriah.sales")
public record SalesSettings(@DefaultValue("PT5M") Duration settlementStuckAfter) {
}
