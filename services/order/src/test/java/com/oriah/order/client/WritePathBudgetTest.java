package com.oriah.order.client;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
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
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class WritePathBudgetTest {

    /** checkout's readTimeout for its calls to order (checkout application.yaml). */
    private static final int CHECKOUT_WAITS_MS = 15_000;

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
    @DisplayName("the worst case of every call on the placement path fits inside checkout's 15s")
    void theWritePathFitsInsideCheckoutsWait() {
        int worst = TOKEN_WORST_MS;
        for (String client : WRITE_PATH.keySet()) {
            FeignClientProperties.FeignClientConfiguration config = configFor(client);
            worst += config.getConnectTimeout() + config.getReadTimeout();
        }

        assertThat(worst)
                .as("customer + catalog + token + shipping, each at its full connect and read time")
                .isLessThanOrEqualTo(CHECKOUT_WAITS_MS);
    }
}
