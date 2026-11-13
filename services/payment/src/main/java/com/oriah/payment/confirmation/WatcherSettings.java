package com.oriah.payment.confirmation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The watcher that announces a confirmation again while order has not answered ({@code oriah.payment.watcher.*}).
 *
 * @param delay           milliseconds between the end of one pass and the start of the next
 * @param maxEmitAttempts how many times a confirmation is announced in all (the first, when it is recorded, counts) before the watcher stops and
 *                        leaves it to a person: it stays in the pending list, flagged stuck, and is logged as an error when it reaches the limit
 */
@ConfigurationProperties(prefix = "oriah.payment.watcher")
public record WatcherSettings(
        @DefaultValue("30000") long delay,
        @DefaultValue("10") int maxEmitAttempts) {
}
