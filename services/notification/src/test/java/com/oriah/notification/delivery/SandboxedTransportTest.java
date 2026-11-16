package com.oriah.notification.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.notification.delivery.SendResult.Outcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock on the door itself. While a stack is not production, every message passes through this wrapper on its way to the mail server, and an
 * address that could be a real mailbox is turned back here even if everything before it let it through.
 */
class SandboxedTransportTest {

    /** Stands in for the real transport and remembers what reached it. */
    private static final class Remembering implements MessageTransport {
        private final List<Email> handedOver = new ArrayList<>();
        private int probes;

        @Override
        public SendResult send(Email email) {
            handedOver.add(email);
            return SendResult.accepted();
        }

        @Override
        public SendResult probe() {
            probes++;
            return SendResult.accepted();
        }
    }

    @Test
    @DisplayName("the transport itself refuses a real-looking address when the stack is not live: the real transport is never called")
    void theTransportItselfRefusesARealLookingAddressWhenNotLive() {
        Remembering real = new Remembering();
        MessageTransport sandboxed = new SandboxedTransport(real);

        SendResult result = sandboxed.send(new Email(UUID.randomUUID(), "someone@example.com", "s", "b"));

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(result.code()).isEqualTo(SendResult.SANDBOX);
        assertThat(real.handedOver).isEmpty();
    }

    @Test
    @DisplayName("an address that can never be a real mailbox goes through, and so does a probe, which sends nothing")
    void aReservedAddressGoesThrough() {
        Remembering real = new Remembering();
        MessageTransport sandboxed = new SandboxedTransport(real);

        assertThat(sandboxed.send(new Email(UUID.randomUUID(), "alice@oriah.local", "s", "b")).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(sandboxed.probe().outcome()).isEqualTo(Outcome.ACCEPTED);

        assertThat(real.handedOver).hasSize(1);
        assertThat(real.probes).isEqualTo(1);
    }
}
