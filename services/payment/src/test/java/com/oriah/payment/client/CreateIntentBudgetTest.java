package com.oriah.payment.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating an intent now asks order whose order it is, and that call must
 * finish well inside the time checkout waits for payment.
 *
 * <h2>Why the sum matters</h2>
 *
 * Checkout gives payment 15s to answer each call (its Feign
 * {@code readTimeout}, checkout {@code application.yaml}), and retries an
 * outage up to three attempts, 500ms apart. If payment's wait for order could
 * run past 15s, checkout would give up on an attempt while payment was still
 * working -- and payment might then write an intent nobody is waiting for.
 * Kept inside the 15s, checkout always hears payment's own answer: 201, the
 * 200 for an intent it already made, a 404, or a 503 it can retry.
 *
 * <p>The worst case -- order taking its full time to connect and its full time
 * to answer -- is asserted, not just described in a comment that the next
 * call added to this path makes untrue. Not counted: payment's own database
 * work after the call, which the 11s left over covers many times.
 *
 * <p>The numbers are read the way Spring Cloud reads them: from the
 * configuration keyed by the {@code @FeignClient} name. A misspelled key leaves
 * feign's 10s/60s defaults in place, which no budget could absorb, so the name
 * is asserted too.
 *
 * <p>Checkout's side is read from checkout's own files, not copied here: a
 * copy stays green when checkout's wait is shortened, which is exactly when
 * the budget stops fitting. Checkout's {@code application.yaml} and each of
 * its profile files are read, bound as Spring binds them, and the shortest
 * wait in any profile is the one payment must fit inside. Not read:
 * config-repo, which carries no Feign settings for checkout today -- a
 * timeout put there would override these files and is not caught.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CreateIntentBudgetTest {

    /** Where checkout's configuration lives, from the root of oriah-api. */
    private static final Path CHECKOUT_RESOURCES = Path.of("services", "checkout", "src", "main", "resources");

    /** Checkout's own name for its payment client: {@code @FeignClient(name = "payment")}. */
    private static final String CHECKOUTS_PAYMENT_CLIENT = "payment";

    /** feign-core's own read timeout, which governs when checkout configures none. */
    private static final int FEIGN_DEFAULT_READ_TIMEOUT_MS = 60_000;

    /** Must match {@code @FeignClient(name = ...)} on {@link OrderClient}. */
    private static final String CLIENT = "order";

    @Autowired
    private FeignClientProperties feignClientProperties;

    @Autowired
    private Environment environment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private FeignClientProperties.FeignClientConfiguration config() {
        FeignClientProperties.FeignClientConfiguration config = feignClientProperties.getConfig().get(CLIENT);
        assertThat(config)
                .as("no '%s' entry in spring.cloud.openfeign.client.config, so feign-core's "
                        + "10s/60s defaults govern the call", CLIENT)
                .isNotNull();
        assertThat(config.getConnectTimeout()).as("%s connect-timeout", CLIENT).isNotNull();
        assertThat(config.getReadTimeout()).as("%s read-timeout", CLIENT).isNotNull();
        return config;
    }

    @Test
    @DisplayName("order has its own timeouts: 1s to connect, 3s to answer")
    void orderHasItsOwnTimeouts() {
        // One order read by id, for one shopper: a single indexed row and its
        // lines. 3s is generous for it.
        FeignClientProperties.FeignClientConfiguration config = config();

        assertThat(config.getConnectTimeout()).isEqualTo(1_000);
        assertThat(config.getReadTimeout()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("the timeouts are bound from the exact property keys, so a typo fails")
    void thePropertyKeysAreTheOnesSpringReads() {
        String base = "spring.cloud.openfeign.client.config." + CLIENT + ".";

        assertThat(environment.getProperty(base + "connect-timeout", Integer.class))
                .as("%sconnect-timeout", base).isEqualTo(1_000);
        assertThat(environment.getProperty(base + "read-timeout", Integer.class))
                .as("%sread-timeout", base).isEqualTo(3_000);
    }

    @Test
    @DisplayName("the config key is the name OrderClient's @FeignClient declares")
    void theConfigKeyIsTheClientName() {
        FeignClient annotation = OrderClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation).as("OrderClient is a Feign client").isNotNull();
        assertThat(annotation.name()).as("@FeignClient(name) on OrderClient").isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("the worst case of payment's call to order fits inside checkout's own wait for payment")
    void theCallToOrderFitsInsideCheckoutsWait() throws IOException {
        FeignClientProperties.FeignClientConfiguration config = config();
        int worst = config.getConnectTimeout() + config.getReadTimeout();

        assertThat(worst)
                .as("order at its full connect and read time, inside one of checkout's attempts "
                        + "(checkout's readTimeout for payment, read from its own files)")
                .isLessThan(checkoutsShortestWaitForPaymentMs());
    }

    /**
     * Checkout's read timeout for its calls to payment, in the profile where it
     * is shortest: {@code application.yaml} alone, and with each
     * {@code application-<profile>.yaml} laid over it, as Spring would.
     */
    private static int checkoutsShortestWaitForPaymentMs() throws IOException {
        Path resources = checkoutResources();
        List<PropertySource<?>> base = documents(resources.resolve("application.yaml"));
        assertThat(base).as("checkout's application.yaml has settings in it").isNotEmpty();

        int shortest = checkoutsWaitForPaymentMs(base);
        try (Stream<Path> files = Files.list(resources)) {
            for (Path profile : files.filter(f -> f.getFileName().toString().matches("application-.+\\.ya?ml")).toList()) {
                List<PropertySource<?>> layered = documents(profile);
                layered.addAll(base);
                shortest = Math.min(shortest, checkoutsWaitForPaymentMs(layered));
            }
        }
        return shortest;
    }

    /**
     * A file's YAML documents, the last first: in Spring a later document in
     * a file overrides an earlier one. A document limited to a profile is read
     * as if that profile were on, which can only make the wait found shorter.
     */
    private static List<PropertySource<?>> documents(Path file) throws IOException {
        List<PropertySource<?>> documents = new ArrayList<>(
                new YamlPropertySourceLoader().load(file.getFileName().toString(), new FileSystemResource(file)));
        Collections.reverse(documents);
        return documents;
    }

    /** First source wins, as in Spring's environment; the client's own entry over {@code default}. */
    private static int checkoutsWaitForPaymentMs(List<PropertySource<?>> sources) {
        FeignClientProperties feign = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.cloud.openfeign.client", FeignClientProperties.class)
                .orElseGet(FeignClientProperties::new);
        for (String name : List.of(CHECKOUTS_PAYMENT_CLIENT, feign.getDefaultConfig())) {
            FeignClientProperties.FeignClientConfiguration client = feign.getConfig().get(name);
            if (client != null && client.getReadTimeout() != null) {
                return client.getReadTimeout();
            }
        }
        return FEIGN_DEFAULT_READ_TIMEOUT_MS;
    }

    /**
     * Found by walking up from the working directory, so the test runs the
     * same from Maven (services/payment) and from an IDE (often the repository
     * root). Not finding it fails: skipping would hide the very gap this reads
     * the files to close.
     */
    private static Path checkoutResources() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(CHECKOUT_RESOURCES);
            if (Files.isRegularFile(candidate.resolve("application.yaml"))) {
                return candidate;
            }
        }
        throw new AssertionError("checkout's " + CHECKOUT_RESOURCES.resolve("application.yaml")
                + " was not found above " + Path.of("").toAbsolutePath()
                + "; payment's budget is measured against checkout's own wait for payment");
    }
}
