package com.oriah.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.events.probe.ProbeApplication;

import static com.oriah.events.Relayed.header;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay runs by itself on {@code oriah.events.relay.delay}: a service
 * only appends, and its facts reach Kafka without anyone calling the relay.
 * (Every other test sets the delay to an hour and drives the relay by hand.)
 */
@SpringBootTest(classes = ProbeApplication.class, properties = {
        "spring.flyway.locations=classpath:db/oriah-events",
        "oriah.events.producer=probe",
        "oriah.events.topics=oriah.probe",
        "oriah.events.outbox.enabled=true",
        "oriah.events.relay.enabled=true",
        "oriah.events.relay.delay=200",
        "spring.cloud.stream.kafka.default.producer.sync=true"
})
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
class OutboxRelayScheduleTest {

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutputDestination output;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("an appended fact is published on the relay's own schedule")
    void publishesOnItsOwnSchedule() {
        String order = UUID.randomUUID().toString();
        AppendedEvent appended = new TransactionTemplate(transactionManager).execute(status ->
                writer.append(new EventDraft("order", order, "order.created", 1, "oriah.probe",
                        Instant.parse("2026-11-09T07:30:00Z"), "system:probe", Map.of("orderId", order))));

        Message<byte[]> message = output.receive(10_000, "oriah.probe");

        assertThat(message).as("published within 10 s by the scheduled relay").isNotNull();
        assertThat(header(message, "event-id")).isEqualTo(appended.eventId().toString());
    }
}
