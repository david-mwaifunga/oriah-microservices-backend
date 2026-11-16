package com.oriah.notification.delivery;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.oriah.notification.delivery.SendResult.Outcome;
import com.oriah.notification.delivery.SmtpSettings.Security;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one class that talks to a mail server, proven against a scripted server on a local socket.
 *
 * <p>What it must get right is not the happy path but WHICH failure it met, because each means something different for the message and for
 * everything queued behind it:
 * <ul>
 *   <li>ACCEPTED: the server took the message (250 after the body);</li>
 *   <li>REFUSED: this address is refused for good (a 5xx answer to RCPT TO, or a text that is not one address);</li>
 *   <li>RECIPIENT_TRY_LATER: this mailbox cannot take mail now (a 4xx answer to RCPT TO); the server itself is fine;</li>
 *   <li>SERVER_TRY_LATER: the server could not be used (no connection, no sign-in, the sender refused, a refusal after the body such as a used-up
 *       daily quota): nothing is wrong with the message or its address, and trying the next message would fail the same way;</li>
 *   <li>UNCERTAIN: the body was handed over and no answer came. It may have arrived.</li>
 * </ul>
 * It never throws, never holds the caller longer than one fixed time, and nothing a server says reaches the log (a refusal often quotes the address).
 */
@ExtendWith(OutputCaptureExtension.class)
class SmtpTransportTest {

    private static final UUID ROW = UUID.fromString("0a0b0c0d-1111-2222-3333-444455556666");
    private static final Email TO_ALICE = new Email(ROW, "alice@oriah.local", "Payment received - thank you",
            "We have received your payment of K180.00 for order 8A1F4B2E.");

    private final ScriptedSmtpServer server = ScriptedSmtpServer.start();

    @AfterEach
    void stop() {
        server.close();
    }

    private SmtpSettings plain() {
        return new SmtpSettings("127.0.0.1", server.port(), Security.NONE, null, null, "no-reply@oriah.local", "Oriah Jewelry", null,
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(8));
    }

    private SmtpSettings signingIn() {
        return new SmtpSettings("127.0.0.1", server.port(), Security.NONE, "shop-account", "shop-password", "no-reply@oriah.local",
                "Oriah Jewelry", null, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(8));
    }

    private SmtpSettings impatient(Duration read, Duration maxSend) {
        return new SmtpSettings("127.0.0.1", server.port(), Security.NONE, null, null, "no-reply@oriah.local", "Oriah Jewelry", null,
                Duration.ofSeconds(2), read, Duration.ofSeconds(2), maxSend);
    }

    private static void is(SendResult result, Outcome outcome, String code) {
        assertThat(result.outcome()).as("outcome (code %s)", result.code()).isEqualTo(outcome);
        assertThat(result.code()).isEqualTo(code);
    }

    @Test
    @DisplayName("a message is handed over with its From, To, subject and body, as plain text")
    void aMessageIsHandedOverWithFromToSubjectAndBodyAsPlainText() {
        SendResult result = new SmtpTransport(plain()).send(TO_ALICE);

        is(result, Outcome.ACCEPTED, null);
        assertThat(server.commands()).anyMatch(c -> c.startsWith("MAIL FROM:<no-reply@oriah.local>"))
                .anyMatch(c -> c.startsWith("RCPT TO:<alice@oriah.local>"));
        assertThat(server.messages()).hasSize(1);
        String message = server.messages().getFirst();
        assertThat(message).contains("From: Oriah Jewelry <no-reply@oriah.local>")
                .contains("To: alice@oriah.local")
                .contains("Subject: Payment received - thank you")
                .containsIgnoringCase("Content-Type: text/plain")
                .contains("We have received your payment of K180.00 for order 8A1F4B2E.");
    }

    @Test
    @DisplayName("the message carries the row's own Message-ID, so a mail system that drops repeats can recognise a second copy")
    void theMessageIdIsTheRowsOwn() {
        new SmtpTransport(plain()).send(TO_ALICE);
        new SmtpTransport(plain()).send(TO_ALICE);

        assertThat(server.messages()).hasSize(2)
                .allMatch(message -> message.contains("Message-ID: <" + ROW + "@oriah.local>"));
    }

    @Test
    @DisplayName("a reply-to address, when the shop has one, is on the message")
    void aReplyToIsCarried() {
        SmtpSettings withReplyTo = new SmtpSettings("127.0.0.1", server.port(), Security.NONE, null, null, "no-reply@oriah.local",
                "Oriah Jewelry", "shop@oriah.local", Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(8));

        new SmtpTransport(withReplyTo).send(TO_ALICE);

        assertThat(server.messages().getFirst()).contains("Reply-To: shop@oriah.local");
    }

    @Test
    @DisplayName("a closed port: the server could not be used, try later")
    void aClosedPortIsServerTryLater() {
        SmtpSettings settings = plain();
        server.close();

        is(new SmtpTransport(settings).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.CONNECT);
    }

    @Test
    @DisplayName("a greeting that is not 'ready': the server could not be used, try later")
    void aGreetingThatIsNotReadyIsServerTryLater() {
        server.reply(ScriptedSmtpServer.GREETING, "554 no service here");

        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.CONNECT);
        assertThat(server.saw("MAIL")).isFalse();
    }

    @Test
    @DisplayName("a refused sign-in is the server's trouble, never the address's: no message fails for it")
    void aRefusedLoginIsServerTryLaterAndFailsNoRow() {
        server.offering("AUTH PLAIN").reply("AUTH", "535 5.7.8 username and password not accepted");

        is(new SmtpTransport(signingIn()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.AUTH);
        assertThat(server.saw("AUTH")).isTrue();
        assertThat(server.saw("MAIL")).isFalse();
    }

    @Test
    @DisplayName("a refused sender (MAIL FROM) is the server's trouble too")
    void aRefusedSenderIsServerTryLater() {
        server.reply("MAIL", "550 5.7.1 sender not allowed");

        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.SMTP_5XX);
        assertThat(server.messages()).isEmpty();
    }

    @Test
    @DisplayName("a recipient the server refuses for good (5xx to RCPT TO) is a refused address")
    void aRefusedRecipientIsRefused() {
        server.reply("RCPT", "550 5.1.1 no such user");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.REFUSED, SendResult.ADDRESS_REFUSED);

        server.reply("RCPT", "554 5.7.1 relay access denied");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.REFUSED, SendResult.ADDRESS_REFUSED);
        assertThat(server.messages()).isEmpty();
    }

    @Test
    @DisplayName("a recipient the server cannot take just now (4xx to RCPT TO: greylisting, a full mailbox) is tried later, and the server is fine")
    void aDeferredRecipientIsRecipientTryLater() {
        server.reply("RCPT", "450 4.2.0 greylisted, try again");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.RECIPIENT_TRY_LATER, SendResult.RCPT_4XX);

        server.reply("RCPT", "452 4.2.2 mailbox full");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.RECIPIENT_TRY_LATER, SendResult.RCPT_4XX);

        server.reply("RCPT", "470 something temporary");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.RECIPIENT_TRY_LATER, SendResult.RCPT_4XX);
    }

    @Test
    @DisplayName("a temporary refusal of the sender, or 'service not available' at any step, is the server's trouble")
    void aTemporaryRefusalIsServerTryLater() {
        server.reply("MAIL", "451 4.3.0 try again later");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.SMTP_4XX);

        server.reply("MAIL", "421 4.3.2 service not available");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.CONNECT);

        server.reply("MAIL", "250 OK").reply("RCPT", "421 4.3.2 shutting down");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.CONNECT);
    }

    @Test
    @DisplayName("a server that will not take the body (anything but 354 to DATA) is the server's trouble")
    void aRefusalToStartTheBodyIsServerTryLater() {
        server.reply("DATA", "451 4.3.0 not now");

        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.SMTP_4XX);
        assertThat(server.messages()).isEmpty();
    }

    @Test
    @DisplayName("a refusal AFTER the body (a used-up daily quota, a blocked sender) is the server's trouble, never a refused address")
    void aRefusalAfterTheBodyIsServerTryLaterNeverARefusedAddress() {
        server.reply(ScriptedSmtpServer.END_OF_BODY, "554 5.7.1 message rejected");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.SMTP_5XX);

        server.reply(ScriptedSmtpServer.END_OF_BODY, "550 5.4.5 daily sending quota exceeded");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.SMTP_5XX);

        server.reply(ScriptedSmtpServer.END_OF_BODY, "451 4.7.0 try again later");
        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.SMTP_4XX);
    }

    @Test
    @DisplayName("a server that stops answering before the body was handed over: nothing arrived, try later")
    void aServerThatNeverAnswersBeforeTheBodyIsServerTryLater() {
        server.silentAt("MAIL");

        is(new SmtpTransport(impatient(Duration.ofMillis(400), Duration.ofSeconds(8))).send(TO_ALICE),
                Outcome.SERVER_TRY_LATER, SendResult.TIMEOUT);
        assertThat(server.messages()).isEmpty();
    }

    @Test
    @DisplayName("a server that never greets is the same")
    void aServerThatNeverGreetsIsServerTryLater() {
        server.silentAt(ScriptedSmtpServer.GREETING);

        assertThat(new SmtpTransport(impatient(Duration.ofMillis(400), Duration.ofSeconds(8))).send(TO_ALICE).outcome())
                .isEqualTo(Outcome.SERVER_TRY_LATER);
    }

    @Test
    @DisplayName("a server that takes the body and never answers: it may have arrived, so the outcome is uncertain")
    void aServerThatTakesTheBodyAndNeverAnswersIsUncertain() {
        server.silentAt(ScriptedSmtpServer.END_OF_BODY);

        is(new SmtpTransport(impatient(Duration.ofMillis(400), Duration.ofSeconds(8))).send(TO_ALICE), Outcome.UNCERTAIN, SendResult.AFTER_DATA);
        assertThat(server.messages()).hasSize(1);
    }

    @Test
    @DisplayName("a connection dropped after the body is uncertain too")
    void aConnectionDroppedAfterTheBodyIsUncertain() {
        server.dropAt(ScriptedSmtpServer.END_OF_BODY);

        is(new SmtpTransport(plain()).send(TO_ALICE), Outcome.UNCERTAIN, SendResult.AFTER_DATA);
    }

    @Test
    @DisplayName("a slow server that keeps answering just inside each timeout is still cut off at the one deadline for the whole send")
    void aSlowButLiveServerIsCutOffAtTheSendDeadline() {
        // Every reply takes 600 ms; each is inside the 2 s read timeout, so no single read ever times out. Greeting, EHLO, MAIL, RCPT, DATA and
        // the final answer would take 3.6 s in all. The whole send is allowed 1.5 s.
        server.slowBy(Duration.ofMillis(600));
        long began = System.nanoTime();

        SendResult result = new SmtpTransport(impatient(Duration.ofSeconds(2), Duration.ofMillis(1500))).send(TO_ALICE);

        Duration took = Duration.ofNanos(System.nanoTime() - began);
        assertThat(took).isLessThan(Duration.ofMillis(2500));
        is(result, Outcome.SERVER_TRY_LATER, SendResult.TIMEOUT);
        assertThat(server.messages()).isEmpty();
    }

    @Test
    @DisplayName("the deadline passing after the body was handed over is uncertain, and the caller still gets its answer on time")
    void theDeadlineAfterTheBodyIsUncertain() {
        server.silentAt(ScriptedSmtpServer.END_OF_BODY);
        long began = System.nanoTime();

        SendResult result = new SmtpTransport(impatient(Duration.ofSeconds(30), Duration.ofMillis(1200))).send(TO_ALICE);

        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofMillis(2500));
        is(result, Outcome.UNCERTAIN, SendResult.AFTER_DATA);
    }

    @Test
    @DisplayName("when the connection must be encrypted and the server does not offer it, the server sees no sign-in and no recipient")
    void aServerThatDoesNotOfferStartTlsSeesNoLoginAndNoRecipient() {
        server.offering("AUTH PLAIN");
        SmtpSettings mustEncrypt = new SmtpSettings("127.0.0.1", server.port(), Security.STARTTLS, "shop-account", "shop-password",
                "no-reply@oriah.local", "Oriah Jewelry", null, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(8));

        is(new SmtpTransport(mustEncrypt).send(TO_ALICE), Outcome.SERVER_TRY_LATER, SendResult.TLS);
        assertThat(server.saw("AUTH")).isFalse();
        assertThat(server.saw("MAIL")).isFalse();
        assertThat(server.saw("RCPT")).isFalse();
    }

    @Test
    @DisplayName("an encrypted session always checks that the server is who its certificate says; the local catcher uses no encryption at all")
    void theSessionAlwaysChecksTheServersName() {
        Properties startTls = SmtpTransport.sessionProperties(new SmtpSettings("smtp.shop.example", 587, Security.STARTTLS, "u", "p",
                "orders@shop.example", "Oriah Jewelry", null, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofSeconds(90)));
        assertThat(startTls).containsEntry("mail.smtp.starttls.enable", "true").containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true").doesNotContainKey("mail.smtp.ssl.enable")
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.connectiontimeout", "5000").containsEntry("mail.smtp.timeout", "30000")
                .containsEntry("mail.smtp.writetimeout", "30000");

        Properties tls = SmtpTransport.sessionProperties(new SmtpSettings("smtp.shop.example", 465, Security.TLS, "u", "p",
                "orders@shop.example", "Oriah Jewelry", null, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofSeconds(90)));
        assertThat(tls).containsEntry("mail.smtp.ssl.enable", "true").containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .doesNotContainKey("mail.smtp.starttls.enable");

        Properties catcher = SmtpTransport.sessionProperties(plain());
        assertThat(catcher).doesNotContainKey("mail.smtp.starttls.enable").doesNotContainKey("mail.smtp.starttls.required")
                .doesNotContainKey("mail.smtp.ssl.enable").doesNotContainKey("mail.smtp.auth");
    }

    @Test
    @DisplayName("a text that is not exactly one address is refused before anything is opened")
    void anUnparsableAddressIsRefusedAndNothingIsOpened() {
        is(new SmtpTransport(plain()).send(new Email(ROW, "Alice <alice@oriah.local>, bob@oriah.local", "s", "b")),
                Outcome.REFUSED, SendResult.ADDRESS_REFUSED);
        is(new SmtpTransport(plain()).send(new Email(ROW, null, "s", "b")), Outcome.REFUSED, SendResult.ADDRESS_REFUSED);

        assertThat(server.connections()).isZero();
    }

    @Test
    @DisplayName("a probe connects, signs in and leaves: it sends nothing")
    void aProbeSignsInAndSendsNothing() {
        server.offering("AUTH PLAIN");

        is(new SmtpTransport(signingIn()).probe(), Outcome.ACCEPTED, null);

        assertThat(server.saw("AUTH")).isTrue();
        assertThat(server.saw("MAIL")).isFalse();
        assertThat(server.messages()).isEmpty();
    }

    @Test
    @DisplayName("a probe of a server that refuses the sign-in says so")
    void aProbeReportsARefusedLogin() {
        server.offering("AUTH PLAIN").reply("AUTH", "535 no");

        is(new SmtpTransport(signingIn()).probe(), Outcome.SERVER_TRY_LATER, SendResult.AUTH);
    }

    @Test
    @DisplayName("whatever goes wrong, the caller gets an answer, never an exception")
    void neverThrows() {
        SmtpSettings nowhere = new SmtpSettings("no-such-host.invalid", 25, Security.NONE, null, null, "no-reply@oriah.local",
                "Oriah Jewelry", null, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(8));

        assertThat(new SmtpTransport(nowhere).send(TO_ALICE).outcome()).isEqualTo(Outcome.SERVER_TRY_LATER);
        assertThat(new SmtpTransport(plain()).send(new Email(ROW, "alice@oriah.local", null, null)).outcome()).isNotNull();
        assertThat(new SmtpTransport(nowhere).probe().outcome()).isEqualTo(Outcome.SERVER_TRY_LATER);
    }

    @Test
    @DisplayName("nothing the server says reaches the log: a refusal often quotes the address")
    void nothingTheServerSaysReachesTheLog(CapturedOutput output) {
        server.reply("RCPT", "550 5.1.1 <alice@oriah.local>: user unknown, ask postmaster@secret.example");

        new SmtpTransport(plain()).send(TO_ALICE);

        assertThat(output.getAll()).doesNotContain("alice@oriah.local").doesNotContain("secret.example").doesNotContain("user unknown");
    }
}
