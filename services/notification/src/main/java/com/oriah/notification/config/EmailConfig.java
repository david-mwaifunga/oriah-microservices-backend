package com.oriah.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.oriah.notification.delivery.Email;
import com.oriah.notification.delivery.MessageTransport;
import com.oriah.notification.delivery.SandboxedTransport;
import com.oriah.notification.delivery.SendResult;
import com.oriah.notification.delivery.SmtpTransport;

/**
 * How this service sends email, put together ONCE when it starts.
 *
 * <p>{@link EmailSetup} is an ordinary singleton built by {@link EmailModeGuard} from the process environment and the settings as they are at
 * that moment. It is not a properties class and not refreshable: nothing that changes settings while the service runs can move it from the
 * local catcher to a real mail server, or change who is told. A setup that would be unsafe throws here, which stops the start.
 *
 * <p>There is exactly one {@link MessageTransport}. Whenever the stack is not live it is the sandboxed one, so the promise "a stack that is not
 * production cannot reach a real mailbox" holds for anything that is ever handed a transport.
 */
@Configuration(proxyBeanMethods = false)
public class EmailConfig {

    @Bean
    EmailSetup emailSetup(ProcessEnv env, NotificationSettings settings) {
        return EmailModeGuard.build(env, settings);
    }

    @Bean
    MessageTransport messageTransport(EmailSetup setup) {
        return switch (setup.mode()) {
            case OFF -> new NoTransport();
            case CATCHER -> new SandboxedTransport(new SmtpTransport(setup.smtp()));
            case LIVE -> new SmtpTransport(setup.smtp());
        };
    }

    /**
     * The transport of a service with no mail server configured. The dispatcher never calls it (every message that would be emailed becomes
     * "not sent, sending off" first); if anything ever did, it opens nothing and says the server cannot be used.
     */
    private static final class NoTransport implements MessageTransport {

        @Override
        public SendResult send(Email email) {
            return SendResult.serverTryLater(SendResult.OTHER);
        }

        @Override
        public SendResult probe() {
            return SendResult.serverTryLater(SendResult.OTHER);
        }
    }
}
