package com.oriah.notification.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.delivery.DispatchHealth;
import com.oriah.notification.delivery.MessageDispatcher;
import com.oriah.notification.delivery.MessageStore;
import com.oriah.notification.delivery.MessageTransport;
import com.oriah.notification.orders.OrderRefs;

/** The one message dispatcher of the running service, built from the one email setup and the one transport. */
@Configuration(proxyBeanMethods = false)
public class DispatchConfig {

    @Bean
    MessageDispatcher messageDispatcher(MessageStore store, ContactBook contacts, OrderRefs orderRefs, DispatchHealth health,
                                        MessageTransport transport, EmailSetup setup, NotificationSettings settings, Clock clock) {
        return new MessageDispatcher(store, contacts, orderRefs, health, transport, setup, settings, clock);
    }
}
