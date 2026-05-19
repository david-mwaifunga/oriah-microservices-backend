package com.oriah.order.events;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Publishes {@link OrderEvent}s onto the configured Kafka topic via
 * Spring Cloud Stream's StreamBridge.
 *
 * <p>For now this is called directly from the service inside the
 * transaction — same dual-write hole as the catalog->Kafka path. The
 * AFTER_COMMIT bridge polish from catalog can be applied here if we
 * want belt-and-braces; for the demo loop the direct publish is enough.
 */
@Component
@Slf4j
public class OrderEventPublisher {

    private static final String BINDING = "orderEvents-out-0";
    private static final String HEADER_EVENT_TYPE = "event-type";

    private final StreamBridge streamBridge;

    public OrderEventPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public void publish(OrderEvent event) {
        String type = "order." + event.newStatus().name().toLowerCase();
        var message = MessageBuilder.withPayload(event)
                .setHeader(HEADER_EVENT_TYPE, type)
                .build();
        boolean sent = streamBridge.send(BINDING, message);
        if (sent) {
            log.info("Published {} orderId={} prev={} new={}",
                    type, event.orderId(), event.previousStatus(), event.newStatus());
        } else {
            log.warn("StreamBridge rejected {} orderId={}", type, event.orderId());
        }
    }
}
