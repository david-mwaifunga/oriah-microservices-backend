package com.oriah.order.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.order.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Marking an order paid asks payment about the intent, and that call -- with
 * room left for order's own work after it -- must finish inside the time
 * checkout waits for order to answer.
 *
 * <h2>Why the sum matters</h2>
 *
 * Checkout calls {@code POST /api/v1/orders/{id}/pay} only AFTER payment has
 * taken the shopper's money. Inside that request order asks payment whether
 * the intent really is a settled payment for this order ({@link PaymentClient},
 * used by {@code PaymentIntentVerifier}), and only then marks the order paid.
 * If order can still be waiting on payment when checkout stops waiting,
 * checkout gives up on a reply that never came -- it does not repeat that,
 * since order may have acted on the request -- and logs PAYMENT CAPTURED
 * WITHOUT ORDER: money taken, order left unpaid, a person has to sort it out.
 * Meanwhile order may yet mark the order paid, or not.
 *
 * <p>The client used to have no timeouts at all, so feign-core's own applied:
 * 10s to connect and 60s to answer, 70s in all -- more than four times
 * checkout's whole wait. Now the worst case -- payment taking its full time to
 * connect and its full time to answer -- must leave at least
 * {@link #ORDERS_OWN_WORK_MS} of checkout's wait for what order does around
 * that call: read the order, then one transaction (attach the payment, move
 * the order to PAID, append its event) and the reply. Asserted, not just
 * described in a comment that the next call added to this path makes untrue.
 *
 * <p>Order's numbers are read the way Spring Cloud reads them: from the
 * configuration keyed by the {@code @FeignClient} name. A misspelled key leaves
 * feign's 10s/60s defaults in place, which no budget could absorb, so the name
 * is asserted too (see {@link CatalogClientTimeoutTest} for why that matters).
 *
 * <p>Checkout's side is read from checkout's own files, not copied here: a
 * copy stays green when checkout's wait is shortened, which is exactly when
 * the budget stops fitting. Which of checkout's clients makes the pay call is
 * read from checkout's source -- the one {@code @FeignClient} interface that
 * declares {@code /api/v1/orders/{id}/pay} -- because Spring Cloud keys a
 * client's settings by its {@code contextId}, or by its name when it has none.
 * Today that is checkout's {@code OrderClient} ({@code name = "order"}, no
 * contextId), which has no entry of its own, so checkout's {@code default}
 * governs: 15s. If checkout gives the pay call a client of its own, this
 * follows it rather than going on reading a wait that no longer applies (the
 * mistake payment's {@code CreateIntentBudgetTest} once made). Then
 * checkout's {@code application.yaml} and each of its profile files are read,
 * bound as Spring binds them, and the shortest wait in any profile is the one
 * order must fit inside -- the same reading as {@link WritePathBudgetTest},
 * copied rather than shared, since no test code is shared between services.
 * Not read: config-repo, which carries no Feign settings for checkout today --
 * a timeout put there would override these files and is not caught.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class PayPathBudgetTest {

    /** Where checkout's configuration lives, from the root of oriah-api. */
    private static final Path CHECKOUT_RESOURCES = Path.of("services", "checkout", "src", "main", "resources");

    /** Where checkout's code lives, from the root of oriah-api. */
    private static final Path CHECKOUT_SOURCES = Path.of("services", "checkout", "src", "main", "java");

    /** The pay endpoint, exactly as a Feign client in checkout maps it. */
    private static final String PAY_MAPPING = "\"/api/v1/orders/{id}/pay\"";

    /** feign-core's own read timeout, which governs when checkout configures none. */
    private static final int FEIGN_DEFAULT_READ_TIMEOUT_MS = 60_000;

    /**
     * What order does around the call to payment must fit in what is left of
     * checkout's wait: at least this much. Reading the order, then one
     * transaction -- attach the payment, move the order to PAID, append
     * order.paid -- and the reply: normally tens of milliseconds, so 2s covers
     * a slow commit, a garbage-collection pause and the reply's way back many
     * times over. The same margin as the placement path's.
     */
    private static final int ORDERS_OWN_WORK_MS = 2_000;

    /** Must match {@code @FeignClient(name = ...)} on {@link PaymentClient}. */
    private static final String CLIENT = "payment";

    @Autowired
    private FeignClientProperties feignClientProperties;

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
    @DisplayName("payment has its own timeouts: 0.5s to connect, 3s to answer")
    void paymentHasItsOwnTimeouts() {
        // One payment intent read by id, for one shopper: a single indexed
        // row. On the checkout path payment has just confirmed this very
        // intent, so it is warm. 3s is generous for it. Half a second to
        // connect, like customer and shipping: payment is on the same
        // network, where a connection opens in a millisecond or two.
        FeignClientProperties.FeignClientConfiguration config = config();

        assertThat(config.getConnectTimeout()).isEqualTo(500);
        assertThat(config.getReadTimeout()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("the config key is the name PaymentClient's @FeignClient declares")
    void theConfigKeyIsTheClientName() {
        FeignClient annotation = PaymentClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation).as("PaymentClient is a Feign client").isNotNull();
        assertThat(annotation.name()).as("@FeignClient(name) on PaymentClient").isEqualTo(CLIENT);
        // A contextId would become the key instead of the name.
        assertThat(annotation.contextId()).as("@FeignClient(contextId) on PaymentClient").isEmpty();
    }

    @Test
    @DisplayName("order's call to payment at its full timeouts leaves 2s of checkout's wait for order.pay "
            + "for order's own work")
    void thePaymentCallFitsInsideCheckoutsWait() throws IOException {
        FeignClientProperties.FeignClientConfiguration config = config();
        int worst = config.getConnectTimeout() + config.getReadTimeout();
        String checkoutsClient = checkoutsClientForPay();
        int checkoutsWait = checkoutsShortestWaitMs(checkoutsClient);

        assertThat(checkoutsWait - worst)
                .as("what is left of checkout's wait for order.pay (%d ms, its '%s' client, read from "
                        + "checkout's own files) once payment has taken its full connect and read time "
                        + "(%d ms): order's own work needs at least %d ms of it",
                        checkoutsWait, checkoutsClient, worst, ORDERS_OWN_WORK_MS)
                .isGreaterThanOrEqualTo(ORDERS_OWN_WORK_MS);
    }

    /**
     * The settings key of the checkout client that calls order.pay: its
     * {@code contextId}, or its name when it has none. Exactly one Feign
     * interface in checkout may map the pay endpoint.
     */
    private static String checkoutsClientForPay() throws IOException {
        Path sources = checkoutPath(CHECKOUT_SOURCES);
        List<Path> declaring;
        try (Stream<Path> files = Files.walk(sources)) {
            declaring = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> read(f).contains(PAY_MAPPING) && read(f).contains("@FeignClient"))
                    .toList();
        }
        assertThat(declaring)
                .as("checkout's Feign interfaces that map %s (under %s)", PAY_MAPPING, sources)
                .hasSize(1);

        String source = read(declaring.get(0));
        Matcher annotation = Pattern.compile("@FeignClient\\s*\\(([^)]*)\\)").matcher(source);
        assertThat(annotation.find()).as("@FeignClient(...) in %s", declaring.get(0)).isTrue();
        String attributes = annotation.group(1);
        for (Pattern key : List.of(
                Pattern.compile("contextId\\s*=\\s*\"([^\"]+)\""),
                Pattern.compile("(?:name|value)\\s*=\\s*\"([^\"]+)\""),
                Pattern.compile("^\\s*\"([^\"]+)\""))) {
            Matcher found = key.matcher(attributes);
            if (found.find()) {
                return found.group(1);
            }
        }
        throw new AssertionError("no contextId or name in @FeignClient(" + attributes + ") in "
                + declaring.get(0));
    }

    /**
     * Checkout's read timeout for this client, in the profile where it is
     * shortest: {@code application.yaml} alone, and with each
     * {@code application-<profile>.yaml} laid over it, as Spring would.
     */
    private static int checkoutsShortestWaitMs(String client) throws IOException {
        Path resources = checkoutPath(CHECKOUT_RESOURCES);
        List<PropertySource<?>> base = documents(resources.resolve("application.yaml"));
        assertThat(base).as("checkout's application.yaml has settings in it").isNotEmpty();

        int shortest = checkoutsWaitMs(client, base);
        try (Stream<Path> files = Files.list(resources)) {
            for (Path profile : files.filter(f -> f.getFileName().toString().matches("application-.+\\.ya?ml")).toList()) {
                List<PropertySource<?>> layered = documents(profile);
                layered.addAll(base);
                shortest = Math.min(shortest, checkoutsWaitMs(client, layered));
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
    private static int checkoutsWaitMs(String client, List<PropertySource<?>> sources) {
        FeignClientProperties feign = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.cloud.openfeign.client", FeignClientProperties.class)
                .orElseGet(FeignClientProperties::new);
        for (String name : List.of(client, feign.getDefaultConfig())) {
            FeignClientProperties.FeignClientConfiguration config = feign.getConfig().get(name);
            if (config != null && config.getReadTimeout() != null) {
                return config.getReadTimeout();
            }
        }
        return FEIGN_DEFAULT_READ_TIMEOUT_MS;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Found by walking up from the working directory, so the test runs the
     * same from Maven (services/order) and from an IDE (often the repository
     * root). Not finding it fails: skipping would hide the very gap this reads
     * the files to close.
     */
    private static Path checkoutPath(Path relative) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("checkout's " + relative + " was not found above " + Path.of("").toAbsolutePath()
                + "; order's budget is measured against checkout's own wait for order.pay");
    }
}
