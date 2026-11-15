package com.oriah.finance.events;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.EventsProperties;
import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance starts in every profile it ships, past every check the event-log module (shared/oriah-events) makes when a service starts: the switches are exactly true or false and
 * every listener can be dead-lettered safely. A profile file is laid over application.yaml when its profile is active, so a setting in it changes what the checks read, in that
 * profile only; the compose stack runs finance with {@code docker}, a developer's IDE with {@code local}. A profile file added later fails
 * {@link #everyProfileFileIsStartedHere()} until it is started here too.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class StartupChecksPerProfileTest {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("default profile (application.yaml alone): finance passes every event-log startup check")
    void defaultProfile() {
        passedEveryCheck(context);
    }

    @Test
    @DisplayName("every profile file finance ships is started here")
    void everyProfileFileIsStartedHere() throws IOException {
        Set<String> started = new TreeSet<>();
        for (Class<?> nested : StartupChecksPerProfileTest.class.getDeclaredClasses()) {
            ActiveProfiles profiles = AnnotatedElementUtils.findMergedAnnotation(nested, ActiveProfiles.class);
            if (nested.isAnnotationPresent(Nested.class) && profiles != null) {
                started.addAll(Arrays.asList(profiles.profiles()));
            }
        }
        Set<String> shipped = new TreeSet<>();
        for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath*:application-*.yaml")) {
            String name = file.getFilename();
            shipped.add(name.substring("application-".length(), name.length() - ".yaml".length()));
        }

        assertThat(shipped).as("the profiles finance ships a file for (application-<profile>.yaml)").isNotEmpty().isEqualTo(started);
    }

    /** A developer's profile: finance run from the IDE against the backing services in Docker. */
    @Nested
    @ActiveProfiles("local")
    class LocalProfile {

        @Autowired
        private ApplicationContext localContext;

        @Test
        @DisplayName("local profile (application-local.yaml): finance passes every event-log startup check")
        void passes() {
            assertThat(localContext.getEnvironment().getActiveProfiles()).contains("local");
            passedEveryCheck(localContext);
        }
    }

    /** The compose stack's profile. */
    @Nested
    @ActiveProfiles("docker")
    @TestPropertySource(properties = {"spring.cloud.config.enabled=false"})
    class DockerProfile {

        @Autowired
        private ApplicationContext dockerContext;

        @Test
        @DisplayName("docker profile (application-docker.yaml): finance passes every event-log startup check")
        void passes() {
            assertThat(dockerContext.getEnvironment().getProperty("spring.cloud.stream.kafka.binder.brokers")).isEqualTo("kafka:9092");
            passedEveryCheck(dockerContext);
        }
    }

    /** The checks ran in this context and passed: the bean is built only after its check has passed, so its presence is the proof. */
    static void passedEveryCheck(ApplicationContext context) {
        ListenerContainerWithDlqAndRetryCustomizer deadLetters = context.getBean(ListenerContainerWithDlqAndRetryCustomizer.class);
        for (String[] binding : new String[][] {{"oriah.order", "finance"}, {"oriah.payment", "finance"}, {"oriah.inventory", "finance"}, {"oriah.procurement", "finance"},
                {"catalog.product", "finance"}, {"catalog.price", "finance"}, {"oriah.replay.finance", "finance"}}) {
            assertThat(deadLetters.retryAndDlqInBinding(binding[0], binding[1])).as(binding[0] + ": does the binder keep its own retry?").isFalse();
        }
        EventsProperties events = context.getBean(EventsProperties.class);
        assertThat(events.inbox().enabled()).isTrue();
        assertThat(events.outbox().enabled()).isFalse();
    }
}
