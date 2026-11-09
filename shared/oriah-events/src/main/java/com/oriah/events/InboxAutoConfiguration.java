package com.oriah.events;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code oriah.events.inbox.enabled=true}: the service gets the {@link Inbox},
 * which applies each received event once in effect.
 *
 * <p>A consumer that starts after its producers have been running also names
 * itself in {@code oriah.events.inbox.catch-up.consumer} and lists the
 * producers it reads in {@code oriah.events.inbox.catch-up.producers}; it then
 * gets {@link CatchUp}, which asks those producers to replay their logs and
 * records how far the replay has arrived.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty("oriah.events.inbox.enabled")
@EnableConfigurationProperties(EventsProperties.class)
public class InboxAutoConfiguration {

    @Bean
    Inbox inbox(DataSource dataSource) {
        return new Inbox(dataSource);
    }

    @Bean
    @ConditionalOnProperty("oriah.events.inbox.catch-up.consumer")
    CatchUp catchUp(DataSource dataSource, PlatformTransactionManager transactionManager, StreamOperations streams,
                    Inbox inbox, EventsProperties properties, Environment environment) {
        EventsProperties.CatchUpSettings settings = properties.inbox().catchUp();
        // Every refusal happens at startup: a catch-up that asks nobody, or
        // whose request may be lost without anyone knowing, would leave the
        // consumer silently incomplete; and the name becomes a topic.
        ConsumerName.require(settings.consumer());
        if (settings.producers().isEmpty()) {
            throw new IllegalStateException("oriah.events.inbox.catch-up.consumer needs "
                    + "oriah.events.inbox.catch-up.producers: the producers it asks for a replay");
        }
        SyncSends.require(environment, "oriah.events.inbox.catch-up.consumer",
                "a replay request Kafka never took would look sent: nothing would answer it, and the catch-up "
                        + "would stay open with nothing to say so");
        return new CatchUp(dataSource, transactionManager, streams, inbox, settings.consumer(),
                settings.producers());
    }
}
