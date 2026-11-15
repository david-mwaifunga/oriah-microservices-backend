package com.oriah.finance.events;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.CannotCreateTransactionException;

import com.oriah.events.CatchUp;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Whatever goes wrong applying an event, the books say so. An event that breaks a promise (IllegalArgument/IllegalState) was always written down as an open error; but a failure of
 * any other kind (a constraint, a lock timeout, the database away for longer than the retries) also ends with the event parked on the dead-letter topic and NOTHING on the books,
 * and a month could then be reported with a sale missing and "0 open errors". Every failure is written down, best effort (a failure to write it down never hides the original),
 * and the original failure still goes on to Kafka's retry and dead-letter handling.
 */
class FinanceConsumerFailureTest {

    private static Message<byte[]> message() {
        return MessageBuilder.withPayload(new byte[0]).setHeader(EventHeaders.EVENT_ID, "00000000-0000-0000-0000-000000000001").setHeader(EventHeaders.EVENT_TYPE, "order.paid")
                .setHeader(EventHeaders.PRODUCER, "order").build();
    }

    @Test
    @DisplayName("a failure that is not a refusal (a constraint violation) is written down as an open error, and still thrown on to be retried and dead-lettered")
    void anyFailureIsWrittenDown() {
        Inbox inbox = mock(Inbox.class);
        RefusalRecorder refusals = mock(RefusalRecorder.class);
        RuntimeException failure = new DataIntegrityViolationException("value too long");
        doThrow(failure).when(inbox).once(any(), any(Map.class), any());
        FinanceEventConsumers.Live live = new FinanceEventConsumers.Live(inbox, mock(FinanceDispatcher.class), refusals);

        assertThatThrownBy(() -> live.accept(message())).isSameAs(failure);

        verify(refusals).refused(eq("00000000-0000-0000-0000-000000000001"), eq("order.paid"), eq("order"), eq(failure));
    }

    @Test
    @DisplayName("the same in the catch-up: a failure of any kind is written down")
    void anyFailureInTheCatchUpIsWrittenDown() {
        CatchUp catchUp = mock(CatchUp.class);
        RefusalRecorder refusals = mock(RefusalRecorder.class);
        RuntimeException failure = new CannotCreateTransactionException("the database is away");
        doThrow(failure).when(catchUp).once(any(), any(Map.class), any());
        FinanceEventConsumers.Replay replay = new FinanceEventConsumers.Replay(catchUp, mock(FinanceDispatcher.class), refusals);

        assertThatThrownBy(() -> replay.accept(message())).isSameAs(failure);

        verify(refusals).refused(eq("00000000-0000-0000-0000-000000000001"), eq("order.paid"), eq("order"), eq(failure));
    }

    @Test
    @DisplayName("if writing the failure down fails too, the ORIGINAL failure is what is thrown")
    void theOriginalFailureIsNeverHidden() {
        Inbox inbox = mock(Inbox.class);
        RefusalRecorder refusals = mock(RefusalRecorder.class);
        RuntimeException failure = new DataIntegrityViolationException("value too long");
        doThrow(failure).when(inbox).once(any(), any(Map.class), any());
        doThrow(new IllegalStateException("the anomaly log is away too")).when(refusals).refused(any(), any(), any(), any());
        FinanceEventConsumers.Live live = new FinanceEventConsumers.Live(inbox, mock(FinanceDispatcher.class), refusals);

        assertThatThrownBy(() -> live.accept(message())).isSameAs(failure);
    }

    @Test
    @DisplayName("an event that applies writes nothing down as a failure, and closes any standing error for it")
    void aSuccessIsNotAFailure() {
        Inbox inbox = mock(Inbox.class);
        RefusalRecorder refusals = mock(RefusalRecorder.class);
        FinanceEventConsumers.Live live = new FinanceEventConsumers.Live(inbox, mock(FinanceDispatcher.class), refusals);

        live.accept(message());

        verify(refusals, never()).refused(any(), any(), any(), any());
        verify(refusals).applied("00000000-0000-0000-0000-000000000001");
        assertThat(true).isTrue();
    }
}
