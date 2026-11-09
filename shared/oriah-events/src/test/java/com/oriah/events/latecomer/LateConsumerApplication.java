package com.oriah.events.latecomer;

import java.util.function.Consumer;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;

/**
 * A stand-in for a consumer service that is added after its producers have
 * been running (finance, Phase 10): it reads the producer's live topic and,
 * through CatchUp, a replay of everything published before it existed.
 *
 * <p>{@code probe.kind} picks what it is: {@code fact} ({@link FactProbe},
 * {@code once}) or {@code projection} ({@link ProjectionProbe},
 * {@code onceInOrder}). Its own package, so the producer probe's component
 * scan never sees these beans, and everything of the module arrives through
 * auto-configuration, as in a real service.
 *
 * <p>Two functions, as a real consumer would have: {@code live} on the
 * producer's topic and {@code replay} on {@code oriah.replay.<consumer>}.
 */
@SpringBootApplication
public class LateConsumerApplication {

    @Bean
    Consumer<Message<byte[]>> live(ProbeConsumer probe) {
        return probe::live;
    }

    @Bean
    Consumer<Message<byte[]>> replay(ProbeConsumer probe) {
        return probe::replay;
    }
}
