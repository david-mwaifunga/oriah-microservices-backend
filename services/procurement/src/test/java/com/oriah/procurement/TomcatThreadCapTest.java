package com.oriah.procurement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared configuration caps Tomcat's request threads, and that cap is what the
 * containers' thread-stack budget is built on.
 *
 * <p>Every container is memory-limited, and Paketo's memory calculator sizes the heap
 * from the limit after setting aside room for thread stacks: {@code BPL_JVM_THREAD_COUNT}
 * threads of one megabyte each. That number only means something if the number of
 * threads is bounded. Tomcat's default is 200 request threads, so without a cap the
 * budget (set in {@code docker-compose/common-config.yml}) is a guess that a burst of
 * requests can overrun, and with no swap the kernel then kills the container with no
 * Java error to read. The cap therefore lives in the one file every servlet service
 * receives from the Config Server, {@code config-repo/application.yaml}.
 *
 * <p>This test proves the two things a silent mistake would break: that the shared file
 * really carries the cap, and that the property name really binds to Tomcat's setting (a
 * mistyped name binds to nothing and leaves the default of 200, looking exactly like a
 * working fix). It lives in procurement only because a service needs Tomcat on its
 * classpath to bind it; the file it reads is the platform's.
 *
 * <p>{@code config-repo} is the folder beside {@code oriah-api}. In a checkout without it
 * the test is skipped, not failed: the file is not this repository's to require.
 */
class TomcatThreadCapTest {

    /** The most request threads any servlet service may run. The compose thread budget is built on this. */
    private static final int CAP = 50;

    private static final Path SHARED = Path.of("..", "..", "..", "config-repo", "application.yaml");

    private static TomcatServerProperties.Threads bind(PropertySource<?> source) {
        return new Binder(ConfigurationPropertySources.from(source))
                .bind("server.tomcat", Bindable.of(TomcatServerProperties.class))
                .orElseGet(TomcatServerProperties::new)
                .getThreads();
    }

    @Test
    @DisplayName("premise: with nothing set, Tomcat allows 200 request threads, which is why a cap is needed")
    void theDefaultIsTwoHundred() {
        assertThat(new TomcatServerProperties().getThreads().getMax()).isEqualTo(200);
    }

    @Test
    @DisplayName("the shared config-repo/application.yaml caps Tomcat's request threads, and the property binds")
    void theSharedConfigurationCapsTheThreads() throws IOException {
        assumeTrue(Files.exists(SHARED), "config-repo is the folder beside oriah-api; this checkout has no copy of it");

        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("shared", new FileSystemResource(SHARED));
        assertThat(loaded).as("the shared file has content").isNotEmpty();

        assertThat(bind(loaded.get(0)).getMax())
                .as("server.tomcat.threads.max in config-repo/application.yaml, as Boot reads it "
                        + "(200 would mean the key is absent or mistyped)")
                .isEqualTo(CAP);
    }

    @Test
    @DisplayName("a mistyped property name leaves the default, so this test can fail")
    void aWrongNameLeavesTheDefault() {
        PropertySource<?> wrong = new org.springframework.core.env.MapPropertySource(
                "wrong", java.util.Map.of("server.tomcat.thread.max", "50"));

        assertThat(bind(wrong).getMax()).isEqualTo(200);
    }
}
