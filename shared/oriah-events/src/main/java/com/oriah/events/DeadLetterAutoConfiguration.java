package com.oriah.events;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.config.ListenerContainerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

/**
 * The module's dead-letter handling for its own Kafka bindings: a message
 * that keeps failing goes to its dead-letter topic, and is never committed
 * -- never dropped -- until that topic has it (see
 * {@link DeadLetterCustomizer}).
 *
 * <p>On only when there is something to handle: the Inbox (its topics, and
 * CatchUp's replay topic) or replay (the replay-request listener the module
 * binds itself). A service that only publishes gets nothing from here.
 *
 * <p>Kafka only. The Kafka binder is an optional dependency of this module:
 * a service without it on its classpath gets none of this, and the rest of
 * the module works as before.
 *
 * <p>The Kafka binder takes exactly one listener container customizer from
 * the service. A service that defines one of its own beside the module's is
 * refused at startup: with two, the module's dead-letter handling could be
 * the one left out, and nothing would say so.
 */
@AutoConfiguration
@ConditionalOnClass(ListenerContainerWithDlqAndRetryCustomizer.class)
@Conditional(DeadLetterAutoConfiguration.InboxOrReplay.class)
@EnableConfigurationProperties(EventsProperties.class)
public class DeadLetterAutoConfiguration {

    private static final String BEAN = "oriahEventsDeadLetters";

    /**
     * The hook the Kafka binder calls for each binding it starts. Every
     * setting it depends on is checked first ({@link DeadLetterStartupCheck}),
     * so a wrong one stops the service here, before anything is bound.
     */
    @Bean(BEAN)
    DeadLetterCustomizer oriahEventsDeadLetters(EventsProperties properties, BindingServiceProperties bindings,
                                                Environment environment, ListableBeanFactory beans) {
        List<String> others = Arrays.stream(beans.getBeanNamesForType(ListenerContainerCustomizer.class, true, false))
                .filter(name -> !BEAN.equals(name)).toList();
        if (!others.isEmpty()) {
            throw new IllegalStateException("oriah-events refuses to start: the service defines its own "
                    + "ListenerContainerCustomizer " + others + " beside the module's. The Kafka binder takes "
                    + "only one, so the module's dead-letter handling could be the one left out. Remove it, or "
                    + "move what it does into oriah-events, so the binder has one customizer to call.");
        }
        DeadLetterStartupCheck.require(properties, bindings, environment);
        return new DeadLetterCustomizer(DeadLetterScope.of(properties));
    }

    /** The Inbox or replay is on. */
    static class InboxOrReplay extends AnyNestedCondition {

        InboxOrReplay() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnBooleanProperty("oriah.events.inbox.enabled")
        static class InboxOn {
        }

        @ConditionalOnBooleanProperty("oriah.events.replay.enabled")
        static class ReplayOn {
        }
    }
}
