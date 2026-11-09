package com.oriah.events.latecomer;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.CatchUp;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;

import static com.oriah.events.latecomer.ProbeConsumer.header;

/**
 * A PROJECTION, as search's product index is: it keeps the newest snapshot of
 * each product, and no row once the product is deleted. Each event is a full
 * snapshot, so an older one arriving late is simply skipped. Sales are not its
 * business, so it declares them ignored.
 *
 * <p>{@code probe.poison} names a product it cannot apply (its effect throws).
 */
@Component
@ConditionalOnProperty(name = "probe.kind", havingValue = "projection")
public class ProjectionProbe implements ProbeConsumer {

    public static final InboxConsumer PRODUCTS = InboxConsumer.named("projection-probe")
            .handles("product.updated", 1)
            .handles("product.deleted", 1)
            .ignores("sale.recorded");

    private final Inbox inbox;
    private final CatchUp catchUp;
    private final JdbcTemplate jdbc;
    private final String poison;

    ProjectionProbe(Inbox inbox, CatchUp catchUp, JdbcTemplate jdbc, @Value("${probe.poison:}") String poison) {
        this.inbox = inbox;
        this.catchUp = catchUp;
        this.jdbc = jdbc;
        this.poison = poison;
    }

    @Override
    @Transactional
    public void live(Message<byte[]> message) {
        inbox.onceInOrder(PRODUCTS, message.getHeaders(), () -> project(message));
    }

    @Override
    @Transactional
    public void replay(Message<byte[]> message) {
        catchUp.onceInOrder(PRODUCTS, message.getHeaders(), () -> project(message));
    }

    private void project(Message<byte[]> message) {
        String productId = header(message, "aggregate-id");
        if (productId.equals(poison)) {
            throw new IllegalStateException("the probe cannot apply product " + productId);
        }
        if ("product.deleted".equals(header(message, "event-type"))) {
            jdbc.update("DELETE FROM probe_product WHERE product_id = ?", productId);
            return;
        }
        jdbc.update("""
                INSERT INTO probe_product (product_id, seq, body) VALUES (?, ?, ?)
                ON CONFLICT (product_id) DO UPDATE SET seq = EXCLUDED.seq, body = EXCLUDED.body""",
                productId, Long.parseLong(header(message, "seq")),
                new String(message.getPayload(), StandardCharsets.UTF_8));
    }
}
