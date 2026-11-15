package com.oriah.finance.events;

import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.CatchUp;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;

import lombok.extern.slf4j.Slf4j;

/**
 * Wires finance to the event log. One live consumer per topic it reads, and ONE replay consumer for the answer to the catch-up it asks every producer for at start (the whole
 * history, from the beginning, so a sale confirmed before finance existed still reaches its month). All of them go through the same Inbox bookkeeping
 * ({@code processed_event}, {@code processed_aggregate}) and the same dispatcher, so an event is applied once whichever stream brings it, and a redelivery changes nothing.
 *
 * <h2>Nothing is repaired, nothing is swallowed</h2>
 *
 * An event that cannot be applied (an undeclared type, a body that breaks the contract, a sale that does not add up) throws: the transaction rolls back with its bookkeeping, the
 * module publishes the event to {@code <topic>.dlq.finance} on its first delivery, and {@link RefusalRecorder} has already written the refusal down as an open error.
 */
@Configuration
@Slf4j
public class FinanceEventConsumers {

    @Bean
    public Consumer<Message<byte[]>> orderEvents(Inbox inbox, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
        return new Live(inbox, dispatcher, refusals);
    }

    @Bean
    public Consumer<Message<byte[]>> paymentEvents(Inbox inbox, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
        return new Live(inbox, dispatcher, refusals);
    }

    @Bean
    public Consumer<Message<byte[]>> inventoryEvents(Inbox inbox, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
        return new Live(inbox, dispatcher, refusals);
    }

    @Bean
    public Consumer<Message<byte[]>> procurementEvents(Inbox inbox, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
        return new Live(inbox, dispatcher, refusals);
    }

    @Bean
    public Consumer<Message<byte[]>> productEvents(Inbox inbox, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
        return new Live(inbox, dispatcher, refusals);
    }

    @Bean
    public Consumer<Message<byte[]>> priceEvents(Inbox inbox, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
        return new Live(inbox, dispatcher, refusals);
    }

    @Bean
    public Consumer<Message<byte[]>> financeReplay(CatchUp catchUp, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
        return new Replay(catchUp, dispatcher, refusals);
    }

    /** Inner handlers, so {@code @Transactional} goes through the proxy (a {@code @Bean} method on this class does not). */
    static class Live implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final FinanceDispatcher dispatcher;
        private final RefusalRecorder refusals;

        Live(Inbox inbox, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
            this.inbox = inbox;
            this.dispatcher = dispatcher;
            this.refusals = refusals;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            try {
                String type = String.valueOf(message.getHeaders().get(EventHeaders.EVENT_TYPE));
                if (FinanceInbox.SNAPSHOTS.contains(type)) {
                    inbox.onceInOrder(FinanceInbox.FINANCE, message.getHeaders(), () -> dispatcher.apply(message));
                } else {
                    inbox.once(FinanceInbox.FINANCE, message.getHeaders(), () -> dispatcher.apply(message));
                }
                refusals.applied(String.valueOf(message.getHeaders().get(EventHeaders.EVENT_ID)));
            } catch (RuntimeException e) {
                recordRefusal(refusals, message, e);
                throw e;
            }
        }
    }

    static class Replay implements Consumer<Message<byte[]>> {
        private final CatchUp catchUp;
        private final FinanceDispatcher dispatcher;
        private final RefusalRecorder refusals;

        Replay(CatchUp catchUp, FinanceDispatcher dispatcher, RefusalRecorder refusals) {
            this.catchUp = catchUp;
            this.dispatcher = dispatcher;
            this.refusals = refusals;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            try {
                String type = String.valueOf(message.getHeaders().get(EventHeaders.EVENT_TYPE));
                if (FinanceInbox.SNAPSHOTS.contains(type)) {
                    catchUp.onceInOrder(FinanceInbox.FINANCE, message.getHeaders(), () -> dispatcher.apply(message));
                } else {
                    catchUp.once(FinanceInbox.FINANCE, message.getHeaders(), () -> dispatcher.apply(message));
                }
                refusals.applied(String.valueOf(message.getHeaders().get(EventHeaders.EVENT_ID)));
            } catch (RuntimeException e) {
                recordRefusal(refusals, message, e);
                throw e;
            }
        }
    }

    private static void recordRefusal(RefusalRecorder refusals, Message<byte[]> message, RuntimeException why) {
        try {
            refusals.refused(String.valueOf(message.getHeaders().get(EventHeaders.EVENT_ID)), String.valueOf(message.getHeaders().get(EventHeaders.EVENT_TYPE)),
                    String.valueOf(message.getHeaders().get(EventHeaders.PRODUCER)), why);
        } catch (RuntimeException failed) {
            log.error("could not write down that an event was refused: {}", failed.toString());
        }
    }
}
