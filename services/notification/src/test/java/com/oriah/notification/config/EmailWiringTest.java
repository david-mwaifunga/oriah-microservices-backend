package com.oriah.notification.config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.delivery.Email;
import com.oriah.notification.delivery.MessageDispatcher;
import com.oriah.notification.delivery.MessageTransport;
import com.oriah.notification.delivery.SandboxedTransport;
import com.oriah.notification.delivery.SendResult;
import com.oriah.notification.delivery.SmtpTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * How the pieces are put together when the service starts: one decision about email ({@link EmailSetup}), made once; one transport, which is the
 * sandboxed one whenever the stack is not live; and one scheduled job that sends, on as shipped.
 */
class EmailWiringTest {

    private static ApplicationContextRunner with(FakeEnv env, Map<String, String> properties) {
        return new ApplicationContextRunner()
                .withBean(ProcessEnv.class, () -> env)
                .withBean(NotificationSettings.class, () -> TestSettings.shippedWith(properties))
                .withUserConfiguration(EmailConfig.class);
    }

    private static FakeEnv live() {
        return new FakeEnv().with("ORIAH_NOTIFICATION_LIVE", "true").with("ORIAH_SMTP_HOST", "smtp.shop-mail.example.org")
                .with("ORIAH_SMTP_USERNAME", "orders@oriahjewelry.co.zm").with("ORIAH_SMTP_PASSWORD", "a-real-password")
                .with("ORIAH_EMAIL_FROM", "orders@oriahjewelry.co.zm").with("ORIAH_ALERT_EMAILS_MONEY", "owner@oriahjewelry.co.zm");
    }

    @Test
    @DisplayName("as shipped there is no mail server: the one transport opens nothing and accepts nothing")
    void asShippedThereIsNoMailServer() {
        with(new FakeEnv(), Map.of()).run(context -> {
            assertThat(context.getBean(EmailSetup.class).mode()).isEqualTo(Mode.OFF);
            assertThat(context).hasSingleBean(MessageTransport.class);
            MessageTransport transport = context.getBean(MessageTransport.class);
            assertThat(transport.send(new Email(UUID.randomUUID(), "alice@oriah.local", "s", "b")).outcome())
                    .isEqualTo(SendResult.Outcome.SERVER_TRY_LATER);
            assertThat(transport.probe().outcome()).isEqualTo(SendResult.Outcome.SERVER_TRY_LATER);
        });
    }

    @Test
    @DisplayName("not live, the only transport there is turns back any address that could be a real mailbox")
    void notLiveTheOnlyTransportBeanIsTheSandboxedOne() {
        with(new FakeEnv(), Map.of("oriah.notification.email.transport", "catcher")).run(context -> {
            assertThat(context.getBean(EmailSetup.class).mode()).isEqualTo(Mode.CATCHER);
            assertThat(context).hasSingleBean(MessageTransport.class);
            assertThat(context.getBean(MessageTransport.class)).isInstanceOf(SandboxedTransport.class);
            assertThat(context.getBean(MessageTransport.class).send(new Email(UUID.randomUUID(), "someone@example.com", "s", "b")).code())
                    .isEqualTo(SendResult.SANDBOX);
        });
    }

    @Test
    @DisplayName("live, the transport is the real one")
    void liveUsesTheRealTransport() {
        with(live(), Map.of()).run(context -> {
            assertThat(context.getBean(EmailSetup.class).mode()).isEqualTo(Mode.LIVE);
            assertThat(context.getBean(MessageTransport.class)).isInstanceOf(SmtpTransport.class);
        });
    }

    @Test
    @DisplayName("a setup that would be unsafe stops the start, with a message that names the setting")
    void aRefusedSetupStopsTheStart() {
        with(new FakeEnv().with("ORIAH_SMTP_HOST", "smtp.somewhere.example.org"), Map.of()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class).hasStackTraceContaining("ORIAH_SMTP_HOST");
        });
    }

    @Test
    @DisplayName("the decision is made once: a refresh of the settings while the service runs cannot change the mode, the mail server or who is told")
    void aRefreshCannotChangeTheModeTheHostOrTheRecipients() {
        with(new FakeEnv().with("ORIAH_ALERT_EMAILS_MONEY", "alerts-money@oriah.local"), Map.of("oriah.notification.email.transport", "catcher"))
                .run(context -> {
                    EmailSetup before = context.getBean(EmailSetup.class);
                    MessageTransport transportBefore = context.getBean(MessageTransport.class);

                    context.publishEvent(new EnvironmentChangeEvent(context, Set.of("oriah.notification.email.transport",
                            "oriah.notification.email.catcher.host", "oriah.notification.alerts.sandbox-recipients.money")));

                    assertThat(context.getBean(EmailSetup.class)).isSameAs(before);
                    assertThat(context.getBean(MessageTransport.class)).isSameAs(transportBefore);
                    assertThat(context.getBeanFactory().getBeanDefinition("emailSetup").getScope()).isIn("", "singleton");
                    assertThat(before.moneyRecipients()).containsExactly("alerts-money@oriah.local");
                });
    }

    // ---- the schedule ----------------------------------------------------------------------------------------------------------------------

    /** Counts its passes instead of making them. */
    static final class CountingDispatcher extends MessageDispatcher {
        final AtomicInteger passes = new AtomicInteger();

        CountingDispatcher() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void run() {
            passes.incrementAndGet();
        }
    }

    private static ApplicationContextRunner scheduled(String enabled) {
        return new ApplicationContextRunner()
                .withBean(MessageDispatcher.class, CountingDispatcher::new)
                .withUserConfiguration(SchedulingConfig.class, DispatchSchedule.class)
                .withPropertyValues("oriah.notification.send.delay=50", "oriah.notification.send.initial-delay=0",
                        "oriah.notification.send.enabled=" + enabled);
    }

    @Test
    @DisplayName("the dispatcher runs on its schedule when switched on, and not at all when switched off")
    void runsOnlyWhenSwitchedOn() {
        scheduled("true").run(context -> {
            CountingDispatcher dispatcher = (CountingDispatcher) context.getBean(MessageDispatcher.class);
            await().atMost(Duration.ofSeconds(10)).until(() -> dispatcher.passes.get() >= 2);
        });
        scheduled("false").run(context -> {
            assertThat(context).doesNotHaveBean(DispatchSchedule.class);
            Thread.sleep(400);
            assertThat(((CountingDispatcher) context.getBean(MessageDispatcher.class)).passes).hasValue(0);
        });
    }

    @Test
    @DisplayName("the shipped file runs the dispatcher every 5 seconds in every profile, on a scheduling thread of its own; only the test configuration turns it off")
    void theJobIsOnInTheShippedFile() throws Exception {
        PropertySource<?> shipped = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
        PropertySource<?> testOnly = new YamlPropertySourceLoader().load("test", new ClassPathResource("config/application.yaml")).getFirst();

        assertThat(shipped.getProperty("oriah.notification.send.enabled")).isEqualTo(true);
        assertThat(shipped.getProperty("oriah.notification.send.delay")).isEqualTo(5000);
        assertThat(shipped.getProperty("oriah.notification.send.initial-delay")).isEqualTo(15000);
        assertThat(shipped.getProperty("spring.task.scheduling.pool.size")).as("one thread per job: the dispatcher and the below-cost check").isEqualTo(2);
        assertThat(shipped.getProperty("oriah.notification.below-cost.enabled")).isEqualTo(true);
        assertThat(shipped.getProperty("oriah.notification.below-cost.delay")).isEqualTo("${ORIAH_NOTIFICATION_BELOW_COST_DELAY:300000}");
        assertThat(shipped.getProperty("oriah.notification.below-cost.initial-delay")).isEqualTo("${ORIAH_NOTIFICATION_BELOW_COST_INITIAL_DELAY:180000}");
        assertThat(shipped.getProperty("oriah.notification.alerts.hold.quiet-from")).as("quoted in the file: unquoted, YAML reads 20:00 as the number 1200")
                .isEqualTo("20:00");
        assertThat(testOnly.getProperty("oriah.notification.below-cost.enabled")).isEqualTo(false);
        assertThat(testOnly.getProperty("oriah.notification.send.enabled")).isEqualTo(false);
    }
}
