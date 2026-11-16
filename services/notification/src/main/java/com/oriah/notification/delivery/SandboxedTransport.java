package com.oriah.notification.delivery;

/**
 * The lock on the door itself: while a stack is NOT production, this is the only transport there is, and it turns back any address that could be
 * a real mailbox ({@link SandboxGate}) without opening a connection.
 *
 * <p>The dispatcher already decides the same thing before it claims a row. This second check is here so that the promise "a stack that is not
 * production cannot reach a real mailbox" does not rest on every caller remembering to ask.
 */
public final class SandboxedTransport implements MessageTransport {

    private final MessageTransport real;

    public SandboxedTransport(MessageTransport real) {
        this.real = real;
    }

    @Override
    public SendResult send(Email email) {
        if (!SandboxGate.mayLeaveASandbox(email.to())) {
            return SendResult.refused(SendResult.SANDBOX);
        }
        return real.send(email);
    }

    /** A probe sends nothing to anyone, so it goes through. */
    @Override
    public SendResult probe() {
        return real.probe();
    }
}
