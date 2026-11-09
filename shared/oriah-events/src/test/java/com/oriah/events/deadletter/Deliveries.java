package com.oriah.events.deadletter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/** Every delivery either probe binding received, in order, with when it arrived. */
@Component
public class Deliveries {

    /** One delivery: the record's key and when the listener got it. */
    public record Delivery(String key, Instant at) {
    }

    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

    void record(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.RECEIVED_KEY);
        deliveries.add(new Delivery(key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(key), Instant.now()));
    }

    /** The deliveries of the record with this key, oldest first. */
    public List<Delivery> of(String key) {
        return deliveries.stream().filter(delivery -> delivery.key().equals(key)).toList();
    }
}
