package com.oriah.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

import com.oriah.payment.client.OrderClient;

/**
 * The card path's one piece of wiring that is not a bean of its own: the Feign client to order, which an intent uses to check that the order
 * is the caller's. It exists only while {@code oriah.payment.card.enabled} is true (the default is off: money reaches an order only by a person
 * confirming it, Phase 7), together with the intent endpoints, the service behind them, the fake card gateway and the reconciler, each of which
 * carries the same condition. Phase 13 turns the path on when a card charge becomes a confirmation.
 *
 * <p>The client is named here, not scanned for: a registrar's default package is the one of the class that carries the annotation.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("oriah.payment.card.enabled")
@EnableFeignClients(clients = OrderClient.class)
public class CardPathConfiguration {
}
