package com.oriah.order.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import com.oriah.order.config.MachineIdentityConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placing an order makes four calls one after another, and together they must
 * fit inside the time checkout waits for order.
 *
 * <h2>Why the sum matters</h2>
 *
 * Checkout gives order 15s to answer a placement (its {@code readTimeout},
 * checkout {@code application.yaml}). Inside that, order asks customer whose
 * address it is, catalog what the lines cost, Keycloak for its own token (when
 * the cached one has run out) and shipping what delivery costs -- in that
 * order, each waiting for the last. If the four can take longer than 15s,
 * checkout gives up and tells the shopper it failed while order is still
 * working, and may yet save an order nobody is waiting for. So the worst case
 * -- every call taking its full time to connect and its full time to answer --
 * is asserted, not just described in a comment that the next added call makes
 * untrue.
 *
 * <p>Each client's numbers are read the way Spring Cloud reads them: from the
 * configuration keyed by the {@code @FeignClient} name. A misspelled key leaves
 * feign's 10s/60s defaults in place, which no sum could absorb -- see
 * {@link CatalogClientTimeoutTest} for why the name is asserted too.
 *
 * <p>Checkout's side is read from checkout's own files, not copied here: a
 * copy stays green when checkout's wait is shortened, which is exactly when
 * the budget stops fitting. Checkout's {@code application.yaml} and each of
 * its profile files are read, bound as Spring binds them, and the shortest
 * wait in any profile is the one order must fit inside -- the same reading as
 * payment's {@code CreateIntentBudgetTest}, copied rather than shared, since
 * no test code is shared between services. Not read: config-repo, which
 * carries no Feign settings for checkout today -- a timeout put there would
 * override these files and is not caught.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class WritePathBudgetTest {

    /** Where checkout's configuration lives, from the root of oriah-api. */
    private static final Path CHECKOUT_RESOURCES = Path.of("services", "checkout", "src", "main", "resources");

    /** Checkout's own name for its order client: {@code @FeignClient(name = "order")}. */
    private static final String CHECKOUTS_ORDER_CLIENT = "order";

    /** feign-core's own read timeout, which governs when checkout configures none. */
    private static final int FEIGN_DEFAULT_READ_TIMEOUT_MS = 60_000;

    /**
     * order's own token request: set in code, not configuration, so it is read
     * from {@code MachineIdentityConfig}'s own constants -- a copy here would
     * stay green while the real timeouts grew past the budget.
     */
    private static final int TOKEN_WORST_MS = (int) (MachineIdentityConfig.TOKEN_CONNECT_TIMEOUT.toMillis()
            + MachineIdentityConfig.TOKEN_READ_TIMEOUT.toMillis());

    /** The Feign clients on the placement path, by their @FeignClient name. */
    private static final Map<String, Class<?>> WRITE_PATH = Map.of(
            "customer", CustomerClient.class,
            "catalog", CatalogPricingClient.class,
            "shipping", ShippingClient.class);

    @Autowired
    private FeignClientProperties feignClientProperties;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private FeignClientProperties.FeignClientConfiguration configFor(String client) {
        FeignClientProperties.FeignClientConfiguration config = feignClientProperties.getConfig().get(client);
        assertThat(config)
                .as("no '%s' entry in spring.cloud.openfeign.client.config, so feign-core's "
                        + "10s/60s defaults govern the call", client)
                .isNotNull();
        assertThat(config.getConnectTimeout()).as("%s connect-timeout", client).isNotNull();
        assertThat(config.getReadTimeout()).as("%s read-timeout", client).isNotNull();
        return config;
    }

    @Test
    @DisplayName("customer has its own timeouts: 1s to connect, 2s to answer")
    void customerHasItsOwnTimeouts() {
        // On the checkout path checkout read this same list moments earlier,
        // so customer is warm and the answer is a handful of rows: 2s is
        // generous for it, and the budget has no more to give.
        FeignClientProperties.FeignClientConfiguration config = configFor("customer");

        assertThat(config.getConnectTimeout()).isEqualTo(1_000);
        assertThat(config.getReadTimeout()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("every write-path config key is the name its @FeignClient declares")
    void theConfigKeysMatchTheClientNames() {
        WRITE_PATH.forEach((name, type) -> assertThat(type.getAnnotation(FeignClient.class).name())
                .as("@FeignClient(name) on %s", type.getSimpleName())
                .isEqualTo(name));
    }

    @Test
    @DisplayName("the worst case of every call on the placement path fits inside checkout's own wait for order")
    void theWritePathFitsInsideCheckoutsWait() throws IOException {
        int worst = TOKEN_WORST_MS;
        for (String client : WRITE_PATH.keySet()) {
            FeignClientProperties.FeignClientConfiguration config = configFor(client);
            worst += config.getConnectTimeout() + config.getReadTimeout();
        }

        // At most, not less than: today the four calls' worst case is EXACTLY
        // checkout's wait (customer 3s + catalog 4s + token 4s + shipping 4s =
        // 15s, each connect plus read), with nothing left for
        // order's own save and event-log append after them. So in the very
        // worst case checkout can still give up first and order save an order
        // nobody is waiting for -- order's application.yaml says so where the
        // timeouts are set. Checkout never repeats a placement that timed out,
        // so that order is not doubled, but it is left CREATED and unpaid.
        // Leaving room means shortening one of order's own timeouts: the
        // owner's call, not this test's.
        assertThat(worst)
                .as("customer + catalog + token + shipping, each at its full connect and read time, inside "
                        + "checkout's readTimeout for order (read from checkout's own files)")
                .isLessThanOrEqualTo(checkoutsShortestWaitForOrderMs());
    }

    /**
     * Checkout's read timeout for its calls to order, in the profile where it
     * is shortest: {@code application.yaml} alone, and with each
     * {@code application-<profile>.yaml} laid over it, as Spring would.
     */
    private static int checkoutsShortestWaitForOrderMs() throws IOException {
        Path resources = checkoutResources();
        List<PropertySource<?>> base = documents(resources.resolve("application.yaml"));
        assertThat(base).as("checkout's application.yaml has settings in it").isNotEmpty();

        int shortest = checkoutsWaitForOrderMs(base);
        try (Stream<Path> files = Files.list(resources)) {
            for (Path profile : files.filter(f -> f.getFileName().toString().matches("application-.+\\.ya?ml")).toList()) {
                List<PropertySource<?>> layered = documents(profile);
                layered.addAll(base);
                shortest = Math.min(shortest, checkoutsWaitForOrderMs(layered));
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
    private static int checkoutsWaitForOrderMs(List<PropertySource<?>> sources) {
        FeignClientProperties feign = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.cloud.openfeign.client", FeignClientProperties.class)
                .orElseGet(FeignClientProperties::new);
        for (String name : List.of(CHECKOUTS_ORDER_CLIENT, feign.getDefaultConfig())) {
            FeignClientProperties.FeignClientConfiguration client = feign.getConfig().get(name);
            if (client != null && client.getReadTimeout() != null) {
                return client.getReadTimeout();
            }
        }
        return FEIGN_DEFAULT_READ_TIMEOUT_MS;
    }

    /**
     * Found by walking up from the working directory, so the test runs the
     * same from Maven (services/order) and from an IDE (often the repository
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
                + "; order's budget is measured against checkout's own wait for order");
    }
}
