package com.oriah.notification.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.contact.EmailAddressRule;
import com.oriah.notification.delivery.SandboxGate;
import com.oriah.notification.delivery.SmtpSettings;
import com.oriah.notification.delivery.SmtpSettings.Security;

/**
 * Decides, once, when the service starts, how it sends email, and refuses to start when the answer would be unsafe.
 *
 * <p><b>The promise it keeps:</b> a stack that is not production cannot reach a real mailbox.
 * <ul>
 *   <li>"Live" is the exact word {@code true} in {@code ORIAH_NOTIFICATION_LIVE}, read from the process environment ({@link ProcessEnv}). No Spring
 *       property can say it: the config repository is shared by every stack.</li>
 *   <li>Not live: the only mail server is the local catcher, whose host must be one of four fixed names, with no sign-in and no encryption.
 *       If any real mail setting ({@code ORIAH_SMTP_*}, {@code ORIAH_EMAIL_FROM}, {@code ORIAH_EMAIL_REPLY_TO}) is present the start is
 *       refused: half of a live setup on a stack that is not live is a mistake somebody should see, not one to work around.</li>
 *   <li>Live: the mail server, its sign-in, the From address and the alert mailboxes come from the environment only. The start is refused when
 *       one is missing, when the connection would not be encrypted, when the mail server is the catcher, when the From address or an alert
 *       mailbox is on a name reserved for testing, or when nobody would be told about a payment.</li>
 * </ul>
 *
 * <p>A refusal names the setting (and the place in a list), never a value: the message of a failed start is printed in a log.
 */
public final class EmailModeGuard {

    private static final Logger log = LoggerFactory.getLogger(EmailModeGuard.class);

    static final String LIVE = "ORIAH_NOTIFICATION_LIVE";
    static final String PAUSED = "ORIAH_NOTIFICATION_SEND_PAUSED";
    static final String SUPPORT_LINE = "ORIAH_NOTIFICATION_SUPPORT_LINE";
    static final String SMTP_HOST = "ORIAH_SMTP_HOST";
    static final String SMTP_PORT = "ORIAH_SMTP_PORT";
    static final String SMTP_USERNAME = "ORIAH_SMTP_USERNAME";
    static final String SMTP_PASSWORD = "ORIAH_SMTP_PASSWORD";
    static final String SMTP_SECURITY = "ORIAH_SMTP_SECURITY";
    static final String EMAIL_FROM = "ORIAH_EMAIL_FROM";
    static final String EMAIL_REPLY_TO = "ORIAH_EMAIL_REPLY_TO";
    static final String ALERTS_MONEY = "ORIAH_ALERT_EMAILS_MONEY";
    static final String ALERTS_COST = "ORIAH_ALERT_EMAILS_COST";

    /** The settings that only a live stack may have. */
    private static final List<String> LIVE_ONLY = List.of(SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, SMTP_SECURITY, EMAIL_FROM,
            EMAIL_REPLY_TO);
    /** The only hosts a stack that is not live may hand mail to: this machine, or the catcher container by its name on the stack's network. */
    private static final Set<String> CATCHER_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "mailcatcher");
    private static final int USUAL_PORT = 587;
    private static final Duration LONGEST_CONNECT = Duration.ofSeconds(30);
    private static final Duration LONGEST_SEND = Duration.ofMinutes(5);
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

    private EmailModeGuard() {
    }

    /**
     * @throws IllegalStateException when the service must not start; the message names the setting at fault
     */
    public static EmailSetup build(ProcessEnv env, NotificationSettings settings) {
        timings(settings);
        boolean paused = "true".equals(env.get(PAUSED));
        String supportLine = trimmed(env.get(SUPPORT_LINE));
        return "true".equals(env.get(LIVE)) ? live(env, settings, supportLine, paused) : notLive(env, settings, supportLine, paused);
    }

    private static EmailSetup notLive(ProcessEnv env, NotificationSettings settings, String supportLine, boolean paused) {
        for (String variable : LIVE_ONLY) {
            if (env.get(variable) != null) {
                throw refused(variable + " is set, but this stack is not live (" + LIVE + " is not exactly 'true'). A stack that is not live "
                        + "sends only to the local mail catcher: remove the variable, or make the stack live");
            }
        }
        // A developer's machine may name its catcher mailboxes in its own settings; the environment wins when it gives them.
        List<String> money = env.get(ALERTS_MONEY) != null ? mailboxes(ALERTS_MONEY, env.get(ALERTS_MONEY))
                : mailboxes("oriah.notification.alerts.sandbox-recipients.money", settings.alerts().sandboxRecipients().money());
        List<String> cost = env.get(ALERTS_COST) != null ? mailboxes(ALERTS_COST, env.get(ALERTS_COST))
                : mailboxes("oriah.notification.alerts.sandbox-recipients.cost", settings.alerts().sandboxRecipients().cost());

        NotificationSettings.Email email = settings.email();
        switch (email.transport()) {
            case "none":
                return new EmailSetup(Mode.OFF, null, money, cost, supportLine, paused);
            case "catcher":
                break;
            default:
                throw refused("oriah.notification.email.transport must be 'none' or 'catcher'");
        }
        if (!CATCHER_HOSTS.contains(email.catcher().host())) {
            throw refused("oriah.notification.email.catcher.host must be one of localhost, 127.0.0.1, ::1 or mailcatcher: "
                    + "a stack that is not live never talks to another mail server");
        }
        if (!SandboxGate.mayLeaveASandbox(email.fromAddress())) {
            throw refused("oriah.notification.email.from-address must be one address on a name reserved for testing (such as oriah.local) "
                    + "while the stack is not live");
        }
        SmtpSettings catcher = new SmtpSettings(email.catcher().host(), email.catcher().port(), Security.NONE, null, null, email.fromAddress(),
                email.fromName(), null, email.smtp().connectTimeout(), email.smtp().readTimeout(), email.smtp().writeTimeout(),
                settings.send().maxSend());
        return new EmailSetup(Mode.CATCHER, catcher, money, cost, supportLine, paused);
    }

    private static EmailSetup live(ProcessEnv env, NotificationSettings settings, String supportLine, boolean paused) {
        String host = required(env, SMTP_HOST);
        if (CATCHER_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw refused(SMTP_HOST + " names the local mail catcher, but this stack is live: every email would land in a test box");
        }
        String username = required(env, SMTP_USERNAME);
        String password = required(env, SMTP_PASSWORD);
        String from = EmailAddressRule.oneAddress(required(env, EMAIL_FROM)).filter(address -> !SandboxGate.mayLeaveASandbox(address))
                .orElseThrow(() -> refused(EMAIL_FROM + " must be exactly one email address, and not on a name reserved for testing"));
        String replyTo = trimmed(env.get(EMAIL_REPLY_TO));
        if (replyTo != null && EmailAddressRule.oneAddress(replyTo).isEmpty()) {
            throw refused(EMAIL_REPLY_TO + " must be exactly one email address");
        }

        List<String> money = realMailboxes(ALERTS_MONEY, env.get(ALERTS_MONEY));
        if (money.isEmpty()) {
            throw refused(ALERTS_MONEY + " must name at least one email address: without it nobody is told when a customer says they have paid");
        }
        List<String> cost = realMailboxes(ALERTS_COST, env.get(ALERTS_COST));
        if (cost.isEmpty()) {
            log.warn("{} is empty: nobody will be emailed when a piece is priced below cost. The alerts are kept and are emailed once an "
                    + "address is given.", ALERTS_COST);
        }

        NotificationSettings.Email email = settings.email();
        SmtpSettings account = new SmtpSettings(host, port(env), security(env), username, password, from, email.fromName(), replyTo,
                email.smtp().connectTimeout(), email.smtp().readTimeout(), email.smtp().writeTimeout(), settings.send().maxSend());
        return new EmailSetup(Mode.LIVE, account, money, cost, supportLine, paused);
    }

    /** The rules that span two settings; they hold in every mode, so a wrong value is found on a developer's machine, not at go-live. */
    private static void timings(NotificationSettings settings) {
        Duration maxSend = settings.send().maxSend();
        if (maxSend.isZero() || maxSend.isNegative() || maxSend.compareTo(LONGEST_SEND) > 0) {
            throw refused("oriah.notification.send.max-send must be more than nothing and at most PT5M");
        }
        NotificationSettings.Smtp smtp = settings.email().smtp();
        if (!positiveAndAtMost(smtp.connectTimeout(), LONGEST_CONNECT)) {
            throw refused("oriah.notification.email.smtp.connect-timeout must be more than nothing and at most PT30S");
        }
        if (!positiveAndAtMost(smtp.readTimeout(), maxSend)) {
            throw refused("oriah.notification.email.smtp.read-timeout must be more than nothing and at most oriah.notification.send.max-send");
        }
        if (!positiveAndAtMost(smtp.writeTimeout(), maxSend)) {
            throw refused("oriah.notification.email.smtp.write-timeout must be more than nothing and at most oriah.notification.send.max-send");
        }
        if (settings.send().lease().compareTo(maxSend.plus(LEASE_MARGIN)) < 0) {
            throw refused("oriah.notification.send.lease must be at least oriah.notification.send.max-send plus PT30S: a message being sent "
                    + "must stay out of reach of any other sender until its send has surely ended");
        }
    }

    private static boolean positiveAndAtMost(Duration value, Duration most) {
        return !value.isZero() && !value.isNegative() && value.compareTo(most) <= 0;
    }

    private static int port(ProcessEnv env) {
        String text = trimmed(env.get(SMTP_PORT));
        if (text == null) {
            return USUAL_PORT;
        }
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException notANumber) {
            // Refused below, with the others.
        }
        throw refused(SMTP_PORT + " must be a port number");
    }

    private static Security security(ProcessEnv env) {
        String text = trimmed(env.get(SMTP_SECURITY));
        if (text == null || text.equals("STARTTLS")) {
            return Security.STARTTLS;
        }
        if (text.equals("TLS")) {
            return Security.TLS;
        }
        throw refused(SMTP_SECURITY + " must be STARTTLS (the usual, port 587) or TLS (port 465): a live stack never sends without encryption");
    }

    private static List<String> mailboxes(String setting, String commaSeparated) {
        try {
            return AddressList.parse(setting, commaSeparated);
        } catch (IllegalArgumentException bad) {
            throw refused(bad.getMessage());
        }
    }

    /** A live stack's alert mailboxes: each exactly one address, and none on a name reserved for testing. */
    private static List<String> realMailboxes(String variable, String commaSeparated) {
        List<String> addresses = mailboxes(variable, commaSeparated);
        for (int i = 0; i < addresses.size(); i++) {
            if (SandboxGate.mayLeaveASandbox(addresses.get(i))) {
                throw refused(variable + ": entry " + (i + 1) + " is on a name reserved for testing, and this stack is live");
            }
        }
        return addresses;
    }

    private static String required(ProcessEnv env, String variable) {
        String value = trimmed(env.get(variable));
        if (value == null) {
            throw refused(variable + " is required on a live stack");
        }
        return value;
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static IllegalStateException refused(String why) {
        return new IllegalStateException("Notification will not start: " + why + ".");
    }
}
