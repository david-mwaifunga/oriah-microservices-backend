package com.oriah.events;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * {@code oriah.events.outbox.enabled=true}: the service gets an
 * {@link OutboxWriter} to append facts to its outbox table.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty("oriah.events.outbox.enabled")
@EnableConfigurationProperties(EventsProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    OutboxWriter outboxWriter(DataSource dataSource, EventsProperties properties) {
        return new OutboxWriter(dataSource, properties.topics());
    }
}
