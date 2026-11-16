package com.oriah.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oriah.notification.contact.EmailAddressRule;
import com.oriah.notification.delivery.BodyAwareMessage.Phase;
import com.oriah.notification.delivery.SmtpSettings.Security;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;

/**
 * The only class that talks to a mail server (plain SMTP, which every mail provider speaks). One connection per message.
 *
 * <p><b>It never throws.</b> Every failure becomes a {@link SendResult} that says what the failure means for the message and for the queue behind it
 * (the table is in {@code SmtpTransportTest}). The rule that matters most: only a 5xx answer to RCPT TO, or a text that is not one address, is a
 * refused ADDRESS. A refused sign-in, a refused sender, or a refusal after the body (how a provider reports a used-up daily quota) is the SERVER's
 * trouble: if those failed the message, one wrong password would mark the whole queue "address refused".
 *
 * <p><b>One wall-clock limit for a whole send.</b> The mail library's timeouts are per socket read, so a server that answers every step just inside
 * the read timeout could hold a send for minutes, longer than the lease the dispatcher took on the row. So the dialogue runs on a thread of its
 * own (a virtual thread), and the caller waits for it at most {@link SmtpSettings#maxSend()}. When the time is up the caller gets its answer at once
 * and the dialogue is interrupted, which closes its socket. Whether that answer is "try later" or "uncertain" is decided by one atomic step on the
 * message's {@link Phase}: if the body had not begun it is called off for good (the body will refuse to be written); if it had, it may have arrived.
 *
 * <p><b>Nothing a server says is logged or returned.</b> A reply often quotes the address. The log line carries the outcome and a fixed code.
 */
public final class SmtpTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpTransport.class);
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    /** "Service not available, closing transmission channel": the server is going away, whatever step it was said at. */
    private static final int SERVICE_NOT_AVAILABLE = 421;

    private final SmtpSettings settings;

    public SmtpTransport(SmtpSettings settings) {
        this.settings = settings;
    }

    @Override
    public SendResult send(Email email) {
        Optional<String> to = EmailAddressRule.oneAddress(email.to());
        if (to.isEmpty()) {
            // Never handed to a mail server: a text that is not exactly one address could be read as two recipients.
            return SendResult.refused(SendResult.ADDRESS_REFUSED);
        }
        SendResult result = underTheDeadline(phase -> deliver(email, to.get(), phase));
        if (result.outcome() != SendResult.Outcome.ACCEPTED) {
            log.warn("Message {} was not accepted by the mail server: {} ({})", email.rowId(), result.outcome(), result.code());
        }
        return result;
    }

    @Override
    public SendResult probe() {
        return underTheDeadline(phase -> {
            Transport transport = null;
            try {
                transport = Session.getInstance(sessionProperties(settings)).getTransport("smtp");
                transport.connect(settings.host(), settings.port(), settings.username(), settings.password());
                return SendResult.accepted();
            } catch (Throwable failure) {
                return whatItMeans(failure, phase.get());
            } finally {
                close(transport);
            }
        });
    }

    private SendResult deliver(Email email, String to, AtomicReference<Phase> phase) {
        Transport transport = null;
        try {
            Session session = Session.getInstance(sessionProperties(settings));
            String messageId = "<" + email.rowId() + "@" + EmailAddressRule.domainOf(settings.fromAddress()) + ">";
            BodyAwareMessage message = new BodyAwareMessage(session, messageId, phase);
            message.setFrom(new InternetAddress(settings.fromAddress(), settings.fromName(), UTF_8));
            if (settings.replyTo() != null && !settings.replyTo().isBlank()) {
                message.setReplyTo(new InternetAddress[] {new InternetAddress(settings.replyTo())});
            }
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
            message.setSubject(email.subject() == null ? "" : email.subject(), UTF_8);
            message.setText(email.body() == null ? "" : email.body(), UTF_8);
            message.setSentDate(new Date());
            message.saveChanges();

            transport = session.getTransport("smtp");
            transport.connect(settings.host(), settings.port(), settings.username(), settings.password());
            transport.sendMessage(message, message.getAllRecipients());
            return SendResult.accepted();
        } catch (Throwable failure) {
            return whatItMeans(failure, phase.get());
        } finally {
            close(transport);
        }
    }

    /**
     * Runs one dialogue on a thread of its own and waits for it at most {@code maxSend}. See the class comment for why.
     */
    private SendResult underTheDeadline(java.util.function.Function<AtomicReference<Phase>, SendResult> dialogue) {
        AtomicReference<Phase> phase = new AtomicReference<>(Phase.BEFORE_BODY);
        CompletableFuture<SendResult> answer = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("smtp-dialogue").unstarted(() -> {
            try {
                answer.complete(dialogue.apply(phase));
            } catch (Throwable unexpected) {
                answer.complete(whatItMeans(unexpected, phase.get()));
            }
        });
        worker.start();
        try {
            return answer.get(settings.maxSend().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException stopped) {
            if (stopped instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // One atomic step decides it: either the body had not begun and now never will, or it had and the message may have arrived.
            boolean nothingLeft = phase.compareAndSet(Phase.BEFORE_BODY, Phase.ABANDONED);
            worker.interrupt();
            return nothingLeft ? SendResult.serverTryLater(SendResult.TIMEOUT) : SendResult.uncertain();
        } catch (ExecutionException impossible) {
            return phase.get() == Phase.BODY_BEGUN ? SendResult.uncertain() : SendResult.serverTryLater(SendResult.OTHER);
        }
    }

    /** The whole classification. {@code phase} is where the send stood when it failed. */
    private SendResult whatItMeans(Throwable failure, Phase phase) {
        if (phase == Phase.BODY_BEGUN) {
            // After the body, only a definite answer from the server says it was NOT taken. A refusal here is how a provider reports a used-up
            // quota or a blocked sender: the server's trouble, never the address's. Silence, a dropped connection or anything else: it may be there.
            int code = failure instanceof SMTPSendFailedException refused ? refused.getReturnCode() : 0;
            if (code >= 400) {
                return SendResult.serverTryLater(code >= 500 ? SendResult.SMTP_5XX : SendResult.SMTP_4XX);
            }
            return SendResult.uncertain();
        }
        return switch (failure) {
            case MailConnectException noConnection -> SendResult.serverTryLater(SendResult.CONNECT);
            case AuthenticationFailedException noSignIn -> SendResult.serverTryLater(SendResult.AUTH);
            // A step other than RCPT TO was refused: MAIL FROM, DATA.
            case SMTPSendFailedException refused -> byCode(refused.getReturnCode());
            // RCPT TO was refused for the one recipient: the reason is the chained exception.
            case SendFailedException recipients when recipients.getNextException() instanceof SMTPAddressFailedException address ->
                    aboutTheRecipient(address.getReturnCode());
            case MessagingException other when settings.security() != Security.NONE && isAboutEncryption(other) ->
                    SendResult.serverTryLater(SendResult.TLS);
            case MessagingException other when hasCause(other, java.net.SocketTimeoutException.class) ->
                    SendResult.serverTryLater(SendResult.TIMEOUT);
            case MessagingException other when hasCause(other, java.io.IOException.class) -> SendResult.serverTryLater(SendResult.TIMEOUT);
            // A greeting that is not 220, an unknown host: the server could not be used.
            case MessagingException other -> SendResult.serverTryLater(SendResult.CONNECT);
            default -> SendResult.serverTryLater(SendResult.OTHER);
        };
    }

    private static SendResult byCode(int code) {
        if (code == SERVICE_NOT_AVAILABLE) {
            return SendResult.serverTryLater(SendResult.CONNECT);
        }
        if (code >= 500) {
            return SendResult.serverTryLater(SendResult.SMTP_5XX);
        }
        if (code >= 400) {
            return SendResult.serverTryLater(SendResult.SMTP_4XX);
        }
        // No readable answer at all (the connection ended): nothing of the message had left.
        return SendResult.serverTryLater(SendResult.TIMEOUT);
    }

    private static SendResult aboutTheRecipient(int code) {
        if (code == SERVICE_NOT_AVAILABLE) {
            return SendResult.serverTryLater(SendResult.CONNECT);
        }
        if (code >= 500) {
            return SendResult.refused(SendResult.ADDRESS_REFUSED);
        }
        if (code >= 400) {
            return SendResult.recipientTryLater();
        }
        return SendResult.serverTryLater(SendResult.OTHER);
    }

    private static boolean isAboutEncryption(MessagingException failure) {
        String said = failure.getMessage();
        return hasCause(failure, SSLException.class) || (said != null && said.contains("STARTTLS"));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> kind) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (kind.isInstance(cause)) {
                return true;
            }
        }
        return failure instanceof MessagingException chained && kind.isInstance(chained.getNextException());
    }

    private static void close(Transport transport) {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (Throwable ignored) {
            // The outcome is already known; a failed goodbye changes nothing.
        }
    }

    /**
     * The mail library's settings for one session, all set explicitly. Encryption is REQUIRED when asked for (the library's "enable" alone goes on
     * in the clear when a server does not offer STARTTLS), and an encrypted session always checks that the server is who its certificate says.
     */
    static Properties sessionProperties(SmtpSettings settings) {
        Properties properties = new Properties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.connectiontimeout", Long.toString(settings.connectTimeout().toMillis()));
        properties.put("mail.smtp.timeout", Long.toString(settings.readTimeout().toMillis()));
        properties.put("mail.smtp.writetimeout", Long.toString(settings.writeTimeout().toMillis()));
        // Do not wait for the answer to QUIT: the outcome is known before the goodbye.
        properties.put("mail.smtp.quitwait", "false");
        // One recipient per message, and it is refused or it is not: never "sent to some".
        properties.put("mail.smtp.sendpartial", "false");
        if (settings.username() != null && !settings.username().isBlank()) {
            properties.put("mail.smtp.auth", "true");
        }
        switch (settings.security()) {
            case STARTTLS -> {
                properties.put("mail.smtp.starttls.enable", "true");
                properties.put("mail.smtp.starttls.required", "true");
                properties.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case TLS -> {
                properties.put("mail.smtp.ssl.enable", "true");
                properties.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case NONE -> {
                // The local mail catcher: no encryption, and (see EmailModeGuard) no other host is ever allowed with it.
            }
        }
        return properties;
    }
}
