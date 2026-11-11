package com.oriah.configserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.config.server.environment.MultipleJGitEnvironmentRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The config server against a real git repository, the way the compose stack runs it.
 *
 * <p>The other config server tests read a bundled folder ({@code native}), so nothing exercised
 * the git backend at all, and nothing could have caught what happened on 2026-11-11: with
 * the repository on a slow Windows mount, every request made the server fetch from it first
 * (the default {@code refresh-rate} of 0 means "every time"), thirteen services asking at once
 * queued behind one another, and the last of them waited longer than Spring's 185 s client
 * limit: {@code Read timed out}, services dead before they started.
 *
 * <p>The refresh rate is the fix: the server fetches at most once in that many seconds and
 * answers every other request from its local clone, in milliseconds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class ConfigServerGitBackendTest {

    private static final Path ORIGIN = createOrigin();
    private static final Path CHECKOUT = createTempDir("config-checkout-");

    @DynamicPropertySource
    static void gitBackend(DynamicPropertyRegistry registry) {
        // A plain path, NOT a file: URI. Spring Cloud Config reads a file: repository in place
        // and never clones it, so every request would run git against the original folder
        // (slow when that is a Windows mount); a path makes it clone once into basedir and
        // fetch from the original no more often than the refresh rate. That is how the compose
        // stack is configured (CONFIG_REPO_URI), so it is what is tested.
        registry.add("spring.cloud.config.server.git.uri", ORIGIN::toString);
        registry.add("spring.cloud.config.server.git.basedir", CHECKOUT::toString);
        registry.add("spring.cloud.config.server.git.default-label", () -> "master");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MultipleJGitEnvironmentRepository repository;

    @Test
    @DisplayName("it serves an application's configuration from the git repository")
    void servesConfigurationFromGit() throws Exception {
        mvc.perform(get("/catalog/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("catalog"))
                .andExpect(jsonPath("$.propertySources[0].source['greeting']").value("hello from git"));
    }

    @Test
    @DisplayName("it fetches from the repository at most once in the refresh rate, not on every request")
    void refreshRateIsSet() {
        // Zero is Spring's default and means a fetch on every single request.
        assertThat(repository.getRefreshRate()).isEqualTo(10);
    }

    @Test
    @DisplayName("it clones the repository when it starts, so the first real request is as fast as the rest")
    void clonesOnStart() {
        assertThat(repository.isCloneOnStart()).isTrue();
    }

    @Test
    @DisplayName("it waits up to 60 s for the repository to answer, not Spring's 5 s, because the mount is slow")
    void gitTimeoutIsGenerous() {
        // Found 2026-11-11: cloning a 0.1 MB repository from the folder mounted into the
        // container (a Windows folder under OneDrive) took longer than the default 5 s to start
        // sending, the clone at start failed with "Read timed out after 5,000 ms", and the
        // config server exited before it served anything.
        assertThat(repository.getTimeout()).isEqualTo(60);
    }

    @Test
    @DisplayName("a change committed to the repository is not fetched again within the refresh rate")
    void secondRequestIsServedFromTheLocalClone() throws Exception {
        mvc.perform(get("/orders/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources[0].source['greeting']").value("first"));

        commit("orders.yml", "greeting: second\n", "change the greeting");

        // inside the 10 s window the answer comes from the clone made a moment ago: no fetch
        mvc.perform(get("/orders/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources[0].source['greeting']").value("first"));

        // and once the window has passed (forced here, so the test does not sleep) the change
        // IS fetched: the services are slow to see a change, never stuck on an old one
        repository.setLastRefresh(0);
        mvc.perform(get("/orders/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources[0].source['greeting']").value("second"));
    }

    private static Path createOrigin() {
        Path dir = createTempDir("config-origin-");
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("master").call()) {
            write(dir.resolve("catalog.yml"), "greeting: hello from git\n");
            write(dir.resolve("orders.yml"), "greeting: first\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("test", "test@example.invalid")
                    .setCommitter("test", "test@example.invalid").setSign(false).call();
        } catch (Exception e) {
            throw new IllegalStateException("could not build the test repository", e);
        }
        return dir;
    }

    private static void commit(String file, String content, String message) {
        try (Git git = Git.open(ORIGIN.toFile())) {
            write(ORIGIN.resolve(file), content);
            git.add().addFilepattern(file).call();
            git.commit().setMessage(message).setAuthor("test", "test@example.invalid")
                    .setCommitter("test", "test@example.invalid").setSign(false).call();
        } catch (Exception e) {
            throw new IllegalStateException("could not commit to the test repository", e);
        }
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
