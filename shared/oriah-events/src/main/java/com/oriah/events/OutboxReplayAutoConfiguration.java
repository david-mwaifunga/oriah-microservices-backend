package com.oriah.events;

import java.util.function.Consumer;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

/**
 * {@code oriah.events.replay.enabled=true}: the service answers replay
 * requests on {@code oriah.outbox.replay-requests} by re-sending its outbox
 * to {@code oriah.replay.<consumer>} (see {@link OutboxReplayer}). The
 * listener's binding is set up by {@link OutboxReplayBinding}.
 */
@AutoConfiguration(after = OutboxRelayAutoConfiguration.class)
@ConditionalOnBooleanProperty("oriah.events.replay.enabled")
@EnableConfigurationProperties(EventsProperties.class)
public class OutboxReplayAutoConfiguration {

    @Bean
    OutboxReplayer outboxReplayer(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  StreamOperations streams, EventsProperties properties, Environment environment) {
        // Both refusals happen at startup. A replay re-sends what the relay
        // publishes, under the relay's producer name; and the listener's
        // consumer group is the service's name, without which every
        // instance would answer every request.
        if (!properties.relay().enabled()) {
            throw new IllegalStateException("oriah.events.replay.enabled needs oriah.events.relay.enabled: "
                    + "a replay re-sends what the relay publishes");
        }
        if (!StringUtils.hasText(environment.getProperty("spring.application.name"))) {
            throw new IllegalStateException("oriah.events.replay.enabled needs spring.application.name: "
                    + "it names the consumer group the replay requests are read in");
        }
        // And one replay step must fit the time Kafka allows the listener
        // between polls, however slowly Kafka answers within its timeouts.
        ReplayStepTime.require(properties.replay().chunkSize(), environment);
        return new OutboxReplayer(dataSource, transactionManager, streams,
                properties.producer(), properties.replay().chunkSize());
    }

    /** The listener itself; {@link OutboxReplayBinding} binds it by this name. */
    @Bean(OutboxReplayBinding.FUNCTION)
    Consumer<Message<byte[]>> outboxReplayRequests(OutboxReplayer replayer) {
        return replayer::handle;
    }
}
