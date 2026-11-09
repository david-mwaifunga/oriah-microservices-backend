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
 * Creating an intent asks order whose order it is, and that call -- with room
 * left for payment's own work after it -- must finish inside the time checkout
 * waits for payment's create.
 *
 * <h2>Why the sum matters</h2>
 *
 * Checkout calls createIntent on a Feign client of its own, contextId
 * {@code payment-create}, which gives payment 6s to answer each attempt
 * (checkout {@code application.yaml}). Because the call is idempotent,
 * checkout repeats an attempt whose answer did not come in time, up to three
 * attempts, 500ms apart. So if payment's create could run past that wait,
 * every attempt would be cut off while payment was still working: the
 * shopper would get a 503 after three wasted attempts, for an intent payment
 * may well have made. Kept inside it, checkout always hears payment's own
 * answer: 201, the 200 for an intent it already made, a 404, a 409, or a 503.
 *
 * <p>The worst case -- order taking its full time to connect and its full time
 * to answer -- must leave at least {@link #PAYMENTS_OWN_WORK_MS} of checkout's
 * wait for what payment does after it: one short transaction (look for the
 * order's live intent, or insert one), normally milliseconds, plus room for a
 * slow commit, a garbage-collection pause and the reply's way back. Asserted,
 * not just described in a comment that the next call added to this path
 * makes untrue.
 *
 * <p>Payment's numbers are read the way Spring Cloud reads them: from the
 * configuration keyed by the {@code @FeignClient} name. A misspelled key leaves
 * feign's 10s/60s defaults in place, which no budget could absorb, so the name
 * is asserted too.
 *
 * <p>Checkout's side is read from checkout's own files, not copied here: a
 * copy stays green when checkout's wait is shortened, which is exactly when
 * the budget stops fitting. Checkout's {@code application.yaml} and each of
 * its profile files are read, bound as Spring binds them -- createIntent's
 * own {@code payment-create} entry over checkout's {@code default} -- and the
 * shortest wait in any profile is the one payment must fit inside. This test
 * used to read checkout's {@code payment} client, which since createIntent got
 * its own client governs only confirm: it stayed green with createIntent's real
 * wait set too short to fit. Not read: config-repo, which carries no Feign
 * settings for checkout today -- a timeout put there would override these
 * files and is not caught.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CreateIntentBudgetTest {

    /** Where checkout's configuration lives, from the root of oriah-api. */
    private static final Path CHECKOUT_RESOURCES = Path.of("services", "checkout", "src", "main", "resources");

    /**
     * The client checkout calls createIntent with -- its {@code contextId},
     * which is what Spring Cloud keys a client's settings by:
     * {@code @FeignClient(name = "payment", contextId = "payment-create")}.
     */
    private static final String CHECKOUTS_CREATE_INTENT_CLIENT = "payment-create";

    /**
     * What payment's create does after order has answered must fit in what is
     * left of checkout's wait: at least this much. The work is one short
     * transaction -- normally milliseconds -- so 2s covers a slow commit, a
     * garbage-collection pause and the reply's way back many times over.
     */
    private static final int PAYMENTS_OWN_WORK_MS = 2_000;

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
    @DisplayName("payment's worst create -- order's full wait plus 2s of its own work -- fits inside checkout's wait for createIntent")
    void theCallToOrderFitsInsideCheckoutsWait() throws IOException {
        FeignClientProperties.FeignClientConfiguration config = config();
        int worst = config.getConnectTimeout() + config.getReadTimeout();
        int checkoutsWait = checkoutsShortestWaitForCreateIntentMs();

        assertThat(checkoutsWait - worst)
                .as("what is left of checkout's wait for createIntent (%d ms, its '%s' client, read from "
                        + "checkout's own files) once order has taken its full connect and read time (%d ms): "
                        + "payment's own work needs at least %d ms of it",
                        checkoutsWait, CHECKOUTS_CREATE_INTENT_CLIENT, worst, PAYMENTS_OWN_WORK_MS)
                .isGreaterThanOrEqualTo(PAYMENTS_OWN_WORK_MS);
    }

    @Test
    @DisplayName("checkout's application.yaml names createIntent's own client, so nothing falls back to its default unseen")
    void checkoutNamesCreateIntentsOwnClient() throws IOException {
        // Without an entry under this name, the reading below would quietly
        // measure checkout's default wait instead -- the very mistake this
        // test used to make by reading the 'payment' client. If checkout
        // renames the client, this goes red and the name above follows it.
        FeignClientProperties feign = bind(documents(checkoutResources().resolve("application.yaml")));

        assertThat(feign.getConfig().get(CHECKOUTS_CREATE_INTENT_CLIENT))
                .as("spring.cloud.openfeign.client.config.%s in checkout's application.yaml",
                        CHECKOUTS_CREATE_INTENT_CLIENT)
                .isNotNull()
                .extracting(FeignClientProperties.FeignClientConfiguration::getReadTimeout)
                .isNotNull();
    }

    /**
     * Checkout's read timeout for createIntent, in the profile where it is
     * shortest: {@code application.yaml} alone, and with each
     * {@code application-<profile>.yaml} laid over it, as Spring would.
     */
    private static int checkoutsShortestWaitForCreateIntentMs() throws IOException {
        Path resources = checkoutResources();
        List<PropertySource<?>> base = documents(resources.resolve("application.yaml"));
        assertThat(base).as("checkout's application.yaml has settings in it").isNotEmpty();

        int shortest = checkoutsWaitForCreateIntentMs(base);
        try (Stream<Path> files = Files.list(resources)) {
            for (Path profile : files.filter(f -> f.getFileName().toString().matches("application-.+\\.ya?ml")).toList()) {
                List<PropertySource<?>> layered = documents(profile);
                layered.addAll(base);
                shortest = Math.min(shortest, checkoutsWaitForCreateIntentMs(layered));
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
    private static int checkoutsWaitForCreateIntentMs(List<PropertySource<?>> sources) {
        FeignClientProperties feign = bind(sources);
        for (String name : List.of(CHECKOUTS_CREATE_INTENT_CLIENT, feign.getDefaultConfig())) {
            FeignClientProperties.FeignClientConfiguration client = feign.getConfig().get(name);
            if (client != null && client.getReadTimeout() != null) {
                return client.getReadTimeout();
            }
        }
        return FEIGN_DEFAULT_READ_TIMEOUT_MS;
    }

    /** Checkout's Feign settings as Spring binds them from these sources. */
    private static FeignClientProperties bind(List<PropertySource<?>> sources) {
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.cloud.openfeign.client", FeignClientProperties.class)
                .orElseGet(FeignClientProperties::new);
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
