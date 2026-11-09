package com.oriah.catalog.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Kafka does with a replay request catalog cannot answer: retry it, then
 * park it on a dead-letter topic of its own, as the event-log module promises
 * ("retried, then dead-lettered").
 *
 * <p>Why it matters: a late consumer (search, inventory) asks catalog to send
 * its whole log again. A request is refused when it is malformed, and a
 * replay fails part-way when Kafka is briefly down while it runs. Without a
 * dead-letter topic the binder logs the last failure and commits past it, so
 * the request is simply gone and the consumer that asked waits for a replay
 * that never comes. Parked, it can be read and sent again. Nothing in the
 * outbox is lost either way; this is about not losing the question.
 *
 * <p>The module binds the listener (destination and group) for every
 * producer; the Kafka-specific dead-letter settings are catalog's own. The
 * test binder has no dead-letter topic to run, so this reads the settings
 * exactly as the Kafka binder binds them, from the context's real
 * environment -- the module's binding included.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReplayRequestBindingTest {

    private static final String BINDING = "outboxReplayRequests-in-0";

    @Autowired
    private BindingServiceProperties bindings;

    @Autowired
    private ConfigurableApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a replay request catalog cannot answer is retried, then parked on oriah.outbox.replay-requests.dlq.catalog")
    void retriedThenDeadLettered() {
        BindingProperties binding = bindings.getBindingProperties(BINDING);
        assertThat(binding.getDestination()).isEqualTo("oriah.outbox.replay-requests");
        assertThat(binding.getGroup()).isEqualTo("catalog");
        // Spring Cloud Stream's own retry, left at its default: tried again
        // before it is given up on.
        assertThat(bindings.getConsumerProperties(BINDING).getMaxAttempts()).isEqualTo(3);

        KafkaConsumerProperties kafka = kafkaConsumerProperties();

        assertThat(kafka.isEnableDlq())
                .as("with no dead-letter topic the binder drops the request after its retries")
                .isTrue();
        // <topic>.dlq.<group>, the name every Oriah dead-letter topic has.
        assertThat(kafka.getDlqName())
                .isEqualTo(binding.getDestination() + ".dlq." + binding.getGroup())
                .isEqualTo("oriah.outbox.replay-requests.dlq.catalog");
    }

    /** Bound the way the Kafka binder binds them: spring.cloud.stream.kafka, defaults merged per binding. */
    private KafkaConsumerProperties kafkaConsumerProperties() {
        KafkaExtendedBindingProperties kafka = new KafkaExtendedBindingProperties();
        Binder.get(context.getEnvironment()).bind("spring.cloud.stream.kafka", Bindable.ofInstance(kafka));
        kafka.setApplicationContext(context);
        return kafka.getExtendedConsumerProperties(BINDING);
    }
}
