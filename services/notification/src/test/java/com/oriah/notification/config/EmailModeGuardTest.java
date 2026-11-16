package com.oriah.notification.config;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.delivery.SmtpSettings.Security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether this service may send real email is decided once, when it starts, and a stack that is not production must be UNABLE to reach a real
 * mailbox.
 *
 * <p>"Live" is one word in the process's own environment ({@code ORIAH_NOTIFICATION_LIVE=true}). It is not a setting: one config repository is
 * served to every stack and wins over a service's own file, so anything a commit could set is not allowed to open the door. The same goes for the
 * mail account, the From address and who receives alerts.
 *
 * <p>Not live: the only mail server is the local catcher (one of four fixed names), with no sign-in and no encryption, and the start is refused
 * if any real mail setting is lying about in the environment. Live: everything comes from the environment, and the start is refused when
 * something is missing or looks like a test value, because a live shop with nobody to tell about a payment must not start quietly.
 *
 * <p>Every refusal names the setting (and, for a list, the place in the list) and never repeats a value: the message is printed in a log.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailModeGuardTest {

    private static final Map<String, String> CATCHER = Map.of("oriah.notification.email.transport", "catcher");

    private static FakeEnv live() {
        return new FakeEnv()
                .with("ORIAH_NOTIFICATION_LIVE", "true")
                .with("ORIAH_SMTP_HOST", "smtp.shop-mail.example.org")
                .with("ORIAH_SMTP_USERNAME", "orders@oriahjewelry.co.zm")
                .with("ORIAH_SMTP_PASSWORD", "a-real-password")
                .with("ORIAH_EMAIL_FROM", "orders@oriahjewelry.co.zm")
                .with("ORIAH_ALERT_EMAILS_MONEY", "owner@oriahjewelry.co.zm");
    }

    private static EmailSetup build(FakeEnv env, Map<String, String> properties) {
        return EmailModeGuard.build(env, TestSettings.shippedWith(properties));
    }

    // ---- not live ------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("as shipped, with nothing set, there is no mail server at all: sending is off")
    void noTransportBuildsNoSender() {
        EmailSetup setup = build(new FakeEnv(), Map.of());

        assertThat(setup.mode()).isEqualTo(Mode.OFF);
        assertThat(setup.smtp()).isNull();
        assertThat(setup.canSend()).isFalse();
    }

    @Test
    @DisplayName("not live with the catcher switched on: the local catcher, no sign-in, no encryption, the test From address")
    void notLiveUsesOnlyTheCatcher() {
        EmailSetup setup = build(new FakeEnv(), CATCHER);

        assertThat(setup.mode()).isEqualTo(Mode.CATCHER);
        assertThat(setup.canSend()).isTrue();
        assertThat(setup.smtp().host()).isEqualTo("localhost");
        assertThat(setup.smtp().port()).isEqualTo(1025);
        assertThat(setup.smtp().security()).isEqualTo(Security.NONE);
        assertThat(setup.smtp().username()).isNull();
        assertThat(setup.smtp().password()).isNull();
        assertThat(setup.smtp().fromAddress()).isEqualTo("no-reply@oriah.local");
        assertThat(setup.smtp().fromName()).isEqualTo("Oriah Jewelry");
        assertThat(setup.smtp().maxSend()).isEqualTo(Duration.ofSeconds(90));
    }

    @ParameterizedTest(name = "catcher host {0} is allowed")
    @ValueSource(strings = {"localhost", "127.0.0.1", "::1", "mailcatcher"})
    @DisplayName("the catcher is one of four fixed names")
    void theCatcherIsOneOfFourNames(String host) {
        assertThat(build(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher",
                "oriah.notification.email.catcher.host", host)).smtp().host()).isEqualTo(host);
    }

    @Test
    @DisplayName("not live, a catcher host that is any other name is refused: a setting cannot point a test stack at a real mail server")
    void notLiveRefusesACatcherHostOutsideTheStack() {
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher",
                "oriah.notification.email.catcher.host", "smtp.gmail.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oriah.notification.email.catcher.host")
                .hasMessageNotContaining("gmail");
    }

    @ParameterizedTest(name = "{0} present while not live refuses the start")
    @ValueSource(strings = {"ORIAH_SMTP_HOST", "ORIAH_SMTP_PORT", "ORIAH_SMTP_USERNAME", "ORIAH_SMTP_PASSWORD", "ORIAH_SMTP_SECURITY",
            "ORIAH_EMAIL_FROM", "ORIAH_EMAIL_REPLY_TO"})
    @DisplayName("not live, any real mail setting in the environment refuses the start")
    void notLiveRefusesAnySmtpVariable(String variable) {
        assertThatThrownBy(() -> build(new FakeEnv().with(variable, "anything-at-all"), CATCHER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(variable)
                .hasMessageContaining("ORIAH_NOTIFICATION_LIVE")
                .hasMessageNotContaining("anything-at-all");
    }

    @Test
    @DisplayName("not live, a From address on a real domain is refused")
    void notLiveRefusesAFromOnARealDomain() {
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher",
                "oriah.notification.email.from-address", "orders@oriahjewelry.co.zm")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oriah.notification.email.from-address")
                .hasMessageNotContaining("oriahjewelry");
    }

    @Test
    @DisplayName("a From name with a line break or another control character is refused, live or not: it would write a header of its own into every email")
    void aFromNameThatCouldWriteAHeaderIsRefused() {
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher",
                "oriah.notification.email.from-name", "Oriah Jewelry\r\nBcc: someone@elsewhere.example.org")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.email.from-name");
        assertThatThrownBy(() -> build(live(), Map.of("oriah.notification.email.from-name", "Oriah\tJewelry")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.email.from-name");
        assertThat(build(live(), Map.of("oriah.notification.email.from-name", "Oriah Jewelry (Lusaka)")).smtp().fromName())
                .isEqualTo("Oriah Jewelry (Lusaka)");
    }

    @Test
    @DisplayName("not live, the From address that is used is the cleaned one that was judged: a space or a line break around it never reaches the mail server")
    void notLiveTheFromAddressIsUsedAsItWasJudged() {
        assertThat(build(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher",
                "oriah.notification.email.from-address", " no-reply@oriah.local\n")).smtp().fromAddress()).isEqualTo("no-reply@oriah.local");
        assertThat(build(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher",
                "oriah.notification.email.from-address", "no-reply@oriah.local ")).smtp().fromAddress()).isEqualTo("no-reply@oriah.local");
    }

    @Test
    @DisplayName("a transport setting that is neither 'none' nor 'catcher' is refused")
    void anUnknownTransportIsRefused() {
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.email.transport", "smtp")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oriah.notification.email.transport");
    }

    @Test
    @DisplayName("not live, alert mailboxes come from the environment when given, else from the developer's own settings")
    void notLiveRecipientsComeFromTheEnvironmentOrTheSandboxSettings() {
        EmailSetup fromEnv = build(new FakeEnv().with("ORIAH_ALERT_EMAILS_MONEY", "alerts-money@oriah.local")
                .with("ORIAH_ALERT_EMAILS_COST", "alerts-cost@oriah.local, alerts-cost@oriah.local"), CATCHER);
        assertThat(fromEnv.moneyRecipients()).containsExactly("alerts-money@oriah.local");
        assertThat(fromEnv.costRecipients()).containsExactly("alerts-cost@oriah.local");

        EmailSetup fromSettings = build(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher",
                "oriah.notification.alerts.sandbox-recipients.money", "dev-money@oriah.local",
                "oriah.notification.alerts.sandbox-recipients.cost", "dev-cost@oriah.local"));
        assertThat(fromSettings.moneyRecipients()).containsExactly("dev-money@oriah.local");
        assertThat(fromSettings.costRecipients()).containsExactly("dev-cost@oriah.local");

        assertThat(build(new FakeEnv(), CATCHER).moneyRecipients()).isEmpty();
    }

    // ---- the live word ---------------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" is not the live word")
    @ValueSource(strings = {"TRUE", "True", "yes", "1", "true ", " true", "on", ""})
    @DisplayName("only the exact word true makes a stack live")
    void onlyTheExactWordTrueIsLive(String word) {
        // With anything else the stack is not live, so the real mail settings beside it refuse the start.
        assertThatThrownBy(() -> build(live().with("ORIAH_NOTIFICATION_LIVE", word), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORIAH_NOTIFICATION_LIVE");
    }

    @Test
    @DisplayName("a setting cannot make a stack live: with the environment empty, a property of the same name changes nothing")
    void aSpringPropertyCannotMakeItLive() {
        EmailSetup setup = build(new FakeEnv(), Map.of("ORIAH_NOTIFICATION_LIVE", "true", "oriah.notification.live", "true",
                "oriah.notification.email.transport", "catcher"));

        assertThat(setup.mode()).isEqualTo(Mode.CATCHER);
    }

    // ---- live ------------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("live: the mail account, the From address and the alert mailboxes all come from the environment; encryption is on by default")
    void liveReadsEverythingFromTheEnvironment() {
        EmailSetup setup = build(live().with("ORIAH_ALERT_EMAILS_COST", "manager@oriahjewelry.co.zm")
                .with("ORIAH_EMAIL_REPLY_TO", "shop@oriahjewelry.co.zm")
                .with("ORIAH_NOTIFICATION_SUPPORT_LINE", " Your system support, 0211 000 000. "), Map.of());

        assertThat(setup.mode()).isEqualTo(Mode.LIVE);
        assertThat(setup.canSend()).isTrue();
        assertThat(setup.smtp().host()).isEqualTo("smtp.shop-mail.example.org");
        assertThat(setup.smtp().port()).isEqualTo(587);
        assertThat(setup.smtp().security()).isEqualTo(Security.STARTTLS);
        assertThat(setup.smtp().username()).isEqualTo("orders@oriahjewelry.co.zm");
        assertThat(setup.smtp().fromAddress()).isEqualTo("orders@oriahjewelry.co.zm");
        assertThat(setup.smtp().fromName()).isEqualTo("Oriah Jewelry");
        assertThat(setup.smtp().replyTo()).isEqualTo("shop@oriahjewelry.co.zm");
        assertThat(setup.moneyRecipients()).containsExactly("owner@oriahjewelry.co.zm");
        assertThat(setup.costRecipients()).containsExactly("manager@oriahjewelry.co.zm");
        assertThat(setup.supportLine()).isEqualTo("Your system support, 0211 000 000.");
    }

    @Test
    @DisplayName("live: a provider's port 465 is asked for by name, with its port")
    void liveMayUseTlsFromTheFirstByte() {
        EmailSetup setup = build(live().with("ORIAH_SMTP_SECURITY", "TLS").with("ORIAH_SMTP_PORT", "465"), Map.of());

        assertThat(setup.smtp().security()).isEqualTo(Security.TLS);
        assertThat(setup.smtp().port()).isEqualTo(465);
    }

    @ParameterizedTest(name = "live without {0} refuses the start")
    @ValueSource(strings = {"ORIAH_SMTP_HOST", "ORIAH_SMTP_USERNAME", "ORIAH_SMTP_PASSWORD", "ORIAH_EMAIL_FROM"})
    @DisplayName("live needs the mail server, its sign-in and the From address")
    void liveNeedsHostLoginAndFrom(String variable) {
        assertThatThrownBy(() -> build(live().without(variable), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(variable);
        assertThatThrownBy(() -> build(live().with(variable, "  "), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(variable);
    }

    @Test
    @DisplayName("live refuses a connection with no encryption, and a security word it does not know")
    void liveRefusesSecurityNone() {
        assertThatThrownBy(() -> build(live().with("ORIAH_SMTP_SECURITY", "NONE"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_SMTP_SECURITY");
        assertThatThrownBy(() -> build(live().with("ORIAH_SMTP_SECURITY", "plain"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_SMTP_SECURITY");
        assertThatThrownBy(() -> build(live().with("ORIAH_SMTP_PORT", "five-eight-seven"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_SMTP_PORT");
    }

    @ParameterizedTest(name = "live with mail server {0} refuses the start")
    @ValueSource(strings = {"localhost", "127.0.0.1", "::1", "mailcatcher", "MailCatcher"})
    @DisplayName("live refuses the catcher: a live shop whose emails all land in a test box would look as if it worked")
    void liveRefusesTheCatcher(String host) {
        assertThatThrownBy(() -> build(live().with("ORIAH_SMTP_HOST", host), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_SMTP_HOST");
    }

    @Test
    @DisplayName("live refuses a From address that is not one address, or that is on a name reserved for testing")
    void liveRefusesAFromOnAReservedDomain() {
        assertThatThrownBy(() -> build(live().with("ORIAH_EMAIL_FROM", "no-reply@oriah.local"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_EMAIL_FROM").hasMessageNotContaining("oriah.local");
        assertThatThrownBy(() -> build(live().with("ORIAH_EMAIL_FROM", "Oriah <orders@oriahjewelry.co.zm>"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_EMAIL_FROM").hasMessageNotContaining("oriahjewelry");
        assertThatThrownBy(() -> build(live().with("ORIAH_EMAIL_REPLY_TO", "a@b.co.zm, c@d.co.zm"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_EMAIL_REPLY_TO");
    }

    @Test
    @DisplayName("live needs at least one mailbox for payment alerts: it is the one alert the client asked for")
    void liveNeedsAnAddressForPaymentAlerts() {
        assertThatThrownBy(() -> build(live().without("ORIAH_ALERT_EMAILS_MONEY"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_ALERT_EMAILS_MONEY");
        assertThatThrownBy(() -> build(live().with("ORIAH_ALERT_EMAILS_MONEY", " , "), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_ALERT_EMAILS_MONEY");
    }

    @Test
    @DisplayName("live may start with nobody to tell about prices below cost, and says so once")
    void liveMayLeaveTheCostListEmptyAndSaysSo(CapturedOutput output) {
        EmailSetup setup = build(live(), Map.of());

        assertThat(setup.costRecipients()).isEmpty();
        assertThat(output.getAll()).contains("ORIAH_ALERT_EMAILS_COST");
    }

    @Test
    @DisplayName("live refuses an alert entry that is not one address, by its place in the list and never by its text")
    void liveRefusesAnAlertAddressThatIsNotOneAddress() {
        assertThatThrownBy(() -> build(live().with("ORIAH_ALERT_EMAILS_MONEY", "owner@oriahjewelry.co.zm, Mary Banda <mary@private.co.zm>"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORIAH_ALERT_EMAILS_MONEY").hasMessageContaining("entry 2")
                .hasMessageNotContaining("mary").hasMessageNotContaining("Banda").hasMessageNotContaining("private");
    }

    @Test
    @DisplayName("live refuses an alert mailbox on a name reserved for testing, by its place in the list")
    void liveRefusesAnAlertAddressOnAReservedDomain() {
        assertThatThrownBy(() -> build(live().with("ORIAH_ALERT_EMAILS_COST", "manager@oriahjewelry.co.zm,alerts-cost@oriah.local"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORIAH_ALERT_EMAILS_COST").hasMessageContaining("entry 2").hasMessageNotContaining("oriah.local");
    }

    @Test
    @DisplayName("live ignores alert mailboxes and a catcher given as settings: only the environment counts")
    void liveIgnoresRecipientsGivenAsProperties() {
        EmailSetup setup = build(live(), Map.of("oriah.notification.alerts.sandbox-recipients.money", "dev-money@oriah.local",
                "oriah.notification.alerts.sandbox-recipients.cost", "dev-cost@oriah.local",
                "oriah.notification.email.transport", "catcher", "oriah.notification.email.from-address", "dev@oriah.local"));

        assertThat(setup.mode()).isEqualTo(Mode.LIVE);
        assertThat(setup.moneyRecipients()).containsExactly("owner@oriahjewelry.co.zm");
        assertThat(setup.costRecipients()).isEmpty();
        assertThat(setup.smtp().fromAddress()).isEqualTo("orders@oriahjewelry.co.zm");
    }

    // ---- both ------------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the pause switch is one word in the environment; sending is off while it is set, in any mode")
    void thePauseSwitch() {
        assertThat(build(live().with("ORIAH_NOTIFICATION_SEND_PAUSED", "true"), Map.of()).paused()).isTrue();
        assertThat(build(live().with("ORIAH_NOTIFICATION_SEND_PAUSED", "true"), Map.of()).canSend()).isFalse();
        assertThat(build(new FakeEnv().with("ORIAH_NOTIFICATION_SEND_PAUSED", "true"), CATCHER).canSend()).isFalse();
        assertThat(build(live(), Map.of()).paused()).isFalse();
        assertThat(build(live().with("ORIAH_NOTIFICATION_SEND_PAUSED", "false"), Map.of()).paused()).isFalse();
        assertThat(build(live().with("ORIAH_NOTIFICATION_SEND_PAUSED", "  "), Map.of()).paused()).isFalse();
    }

    @ParameterizedTest(name = "ORIAH_NOTIFICATION_SEND_PAUSED={0} refuses the start")
    @ValueSource(strings = {"yes", "TRUE", "True", "1", "on", "paused"})
    @DisplayName("the pause switch never fails open: a word that is neither true nor false refuses the start, so 'I stopped the emails' is never silently untrue")
    void aMisspelledPauseRefusesTheStart(String word) {
        assertThatThrownBy(() -> build(live().with("ORIAH_NOTIFICATION_SEND_PAUSED", word), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_NOTIFICATION_SEND_PAUSED");
        assertThatThrownBy(() -> build(new FakeEnv().with("ORIAH_NOTIFICATION_SEND_PAUSED", word), CATCHER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_NOTIFICATION_SEND_PAUSED");
    }

    @Test
    @DisplayName("a reminder interval of nothing, or less, is refused: the daily reminder is stepped forward by it until it lies ahead")
    void aReminderIntervalOfNothingIsRefused() {
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.alerts.settlement.remind-every", "PT0S")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.alerts.settlement.remind-every");
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.alerts.settlement.remind-every", "-PT1H")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.alerts.settlement.remind-every");
    }

    @Test
    @DisplayName("the time a row is kept out of reach must outlast the longest send by half a minute, or two senders could hold one message")
    void theLeaseMustOutlastTheLongestSend() {
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.send.max-send", "PT90S", "oriah.notification.send.lease", "PT100S")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.send.lease");
        assertThat(build(new FakeEnv(), Map.of("oriah.notification.send.max-send", "PT90S", "oriah.notification.send.lease", "PT2M"))).isNotNull();
    }

    @Test
    @DisplayName("each mail timeout must fit inside the longest send, and the longest send has a ceiling of its own")
    void theTimeoutsMustFitInsideTheLongestSend() {
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.email.smtp.read-timeout", "PT2M")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.email.smtp.read-timeout");
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.email.smtp.write-timeout", "PT2M")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.email.smtp.write-timeout");
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.email.smtp.connect-timeout", "PT31S")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.email.smtp.connect-timeout");
        assertThatThrownBy(() -> build(new FakeEnv(), Map.of("oriah.notification.send.max-send", "PT6M", "oriah.notification.send.lease", "PT10M")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.notification.send.max-send");
    }

    @Test
    @DisplayName("what was decided never prints a password or an address")
    void theSetupPrintsNoSecret() {
        String printed = build(live(), Map.of()).toString();

        assertThat(printed).doesNotContain("a-real-password").doesNotContain("oriahjewelry").contains("LIVE");
    }
}
