package com.oriah.payment.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating an intent (the card path, off unless {@code oriah.payment.card.enabled}) asks order whose order it is. That call must not be left to Feign's
 * own defaults of 10 s to connect and 60 s to answer: a hanging order would hold payment, and whoever is waiting on payment, for a minute. With these
 * timeouts a hanging order costs payment at most 1 s + 3 s.
 *
 * <p>Payment's numbers are read the way Spring Cloud reads them: from the configuration keyed by the {@code @FeignClient} name. A misspelled key leaves
 * Feign's defaults in place, which no budget could absorb, so the name is asserted too. {@code CreateIntentOwnershipTest.orderSlowIsServiceUnavailable}
 * proves the end result: a slow order is a 503 in well under the 60 s default.
 *
 * <p>This class used to fit those four seconds inside checkout's own wait for payment's create, reading checkout's {@code application.yaml} for its
 * {@code payment-create} client. Phase 7 removed that call: checkout no longer touches money, it places an order and staff confirm the payment. Nothing calls
 * payment's create until ZynlePay (Phase 13) gives the card path a caller, and THAT caller's wait must then be set so that it leaves room for these four
 * seconds plus payment's own write: restate the budget, and read the caller's configuration in this test, when it exists.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CreateIntentBudgetTest {

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
}
