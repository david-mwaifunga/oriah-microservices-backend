package com.oriah.notification.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * A message that knows whether its body has begun to leave, and that keeps the Message-ID it was given.
 *
 * <p><b>Why the body matters.</b> The mail library writes a message by calling {@link #writeTo(OutputStream, String[])}, and it does so only after
 * the server has answered 354 to DATA. Until that call nothing of the message has left, so a failure means "it did not arrive, try later". After
 * it, a failure with no answer from the server means "it may have arrived". The transport cannot tell the two apart from the exception alone (a
 * timeout looks the same at every step), so the message itself notes the moment.
 *
 * <p>The note is also the place a send is called off: once the deadline has {@linkplain Phase#ABANDONED abandoned} a send whose body had not
 * begun, the body refuses to be written, so "nothing arrived" stays true even if the worker thread is a moment behind.
 *
 * <p><b>Why the Message-ID is fixed.</b> The library would give each try a new id. A row that is sent twice (after a stop between the server's
 * acceptance and our record of it) carries the same id both times, which lets a mail system that drops repeats recognise the second copy.
 */
final class BodyAwareMessage extends MimeMessage {

    /** Where a send stands, as far as "could anything have arrived?" is concerned. */
    enum Phase {
        /** Nothing of the message has left. */
        BEFORE_BODY,
        /** The body has begun to leave: from here on, silence from the server means it may have arrived. */
        BODY_BEGUN,
        /** The deadline passed before the body began; the body will not be written. */
        ABANDONED
    }

    private final String messageId;
    private final AtomicReference<Phase> phase;

    BodyAwareMessage(Session session, String messageId, AtomicReference<Phase> phase) {
        super(session);
        this.messageId = messageId;
        this.phase = phase;
    }

    @Override
    protected void updateMessageID() throws MessagingException {
        setHeader("Message-ID", messageId);
    }

    @Override
    public void writeTo(OutputStream os, String[] ignoreList) throws IOException, MessagingException {
        if (!phase.compareAndSet(Phase.BEFORE_BODY, Phase.BODY_BEGUN)) {
            throw new IOException("this send was called off before its body began");
        }
        super.writeTo(os, ignoreList);
    }
}
