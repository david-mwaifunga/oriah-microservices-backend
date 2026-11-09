package com.oriah.events.latecomer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
 * A FACT consumer, as finance will be: each sale is a thing that happened,
 * applied exactly once, in whatever order it arrives. Products are not its
 * business, so it declares them ignored.
 *
 * <p>{@code probe.poison} names a sale it cannot apply (its effect throws),
 * standing in for a record that fails and is dead-lettered.
 */
@Component
@ConditionalOnProperty(name = "probe.kind", havingValue = "fact")
public class FactProbe implements ProbeConsumer {

    public static final InboxConsumer SALES = InboxConsumer.named("fact-probe")
            .handles("sale.recorded", 1)
            .ignores("product.updated", "product.deleted");

    private final Inbox inbox;
    private final CatchUp catchUp;
    private final JdbcTemplate jdbc;
    private final String poison;

    FactProbe(Inbox inbox, CatchUp catchUp, JdbcTemplate jdbc, @Value("${probe.poison:}") String poison) {
        this.inbox = inbox;
        this.catchUp = catchUp;
        this.jdbc = jdbc;
        this.poison = poison;
    }

    @Override
    @Transactional
    public void live(Message<byte[]> message) {
        inbox.once(SALES, message.getHeaders(), () -> record(message));
    }

    @Override
    @Transactional
    public void replay(Message<byte[]> message) {
        catchUp.once(SALES, message.getHeaders(), () -> record(message));
    }

    private void record(Message<byte[]> message) {
        String saleId = header(message, "aggregate-id");
        if (saleId.equals(poison)) {
            throw new IllegalStateException("the probe cannot apply sale " + saleId);
        }
        jdbc.update("INSERT INTO probe_sale (event_id, sale_id, body) VALUES (?, ?, ?)",
                UUID.fromString(header(message, "event-id")), saleId,
                new String(message.getPayload(), StandardCharsets.UTF_8));
    }
}
