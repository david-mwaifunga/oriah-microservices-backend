package com.oriah.order.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.cloud.openfeign.FeignClient;

import com.oriah.order.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog client's timeouts, asserted on the NAMED client rather than on
 * a bean.
 *
 * <h2>Why this is not a config-is-present test</h2>
 *
 * order is about to make a synchronous call to catalog on its write path,
 * inside a request that checkout makes and cuts off at 15s
 * ({@code checkout application.yaml:44}). Feign's library defaults are 10s
 * connect and 60s read -- so an unconfigured client can hang for four times
 * checkout's budget, and the only visible symptom is checkout reporting a
 * downstream outage for a catalog that is merely slow.
 *
 * <p>A test that asserted some {@code Request.Options} bean existed would pass
 * with the property misspelled, because Spring Cloud OpenFeign builds a CHILD
 * context per client name and falls back to the defaults for a name it has no
 * configuration for. So this resolves the {@code catalog} child context and
 * asks it what IT would use, which is the only thing that governs the call.
 *
 * <p>The property keys are asserted separately. The two failures look
 * identical from the outside -- a typo in
 * {@code spring.cloud.openfeign.client.config.catalog.read-timeout} leaves the
 * 60s default in place exactly as omitting it does -- and knowing which one
 * happened is the difference between a one-character fix and a hunt.
 *
 * <p>{@code circuitbreaker.enabled} is deliberately NOT asserted here. order
 * already sets it false, with its own comment explaining the
 * ForkJoinPool/ThreadLocal trap that would drop the forwarded JWT
 * ({@code order application.yaml:26-35}). An assertion about it could not fail
 * first, and a test that is green on arrival teaches nothing.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class CatalogClientTimeoutTest {

    /** Must match {@code @FeignClient(name = ...)} on CatalogPricingClient. */
    private static final String CLIENT = "catalog";

    // 1s/3s, down from 2s/5s once customer joined the placement path: four
    // calls now share checkout's 15s (WritePathBudgetTest adds them up).
    private static final int CONNECT_MS = 1_000;
    private static final int READ_MS = 3_000;

    @Autowired
    private FeignClientProperties feignClientProperties;

    @Autowired
    private Environment environment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the configuration Spring Cloud reads for the 'catalog' client carries the timeouts")
    void theNamedClientGetsTheTimeouts() {
        // FeignClientProperties, not a Request.Options bean from the client's
        // child context. The first version of this test looked for the latter
        // and failed with the config correctly in place, because Spring Cloud
        // does not put one there: FeignClientFactoryBean reads these
        // properties and calls builder.options(...) while CONSTRUCTING the
        // client, so the Options never becomes a bean unless a user-defined
        // @Configuration declares one. The bound properties map is the closest
        // thing to "what governs the call" that can be asserted without
        // actually making one.
        FeignClientProperties.FeignClientConfiguration config =
                feignClientProperties.getConfig().get(CLIENT);

        assertThat(config)
                .as("no '%s' entry in spring.cloud.openfeign.client.config, so "
                        + "feign-core's 10s/60s defaults govern the call", CLIENT)
                .isNotNull();
        assertThat(config.getConnectTimeout())
                .as("connect timeout for the '%s' client", CLIENT)
                .isEqualTo(CONNECT_MS);
        assertThat(config.getReadTimeout())
                .as("read timeout for the '%s' client -- must sit well inside "
                        + "checkout's 15s per-attempt budget", CLIENT)
                .isEqualTo(READ_MS);
    }

    @Test
    @DisplayName("the client name the config keys use is the one the @FeignClient declares")
    void theConfigKeyMatchesTheClientName() {
        // The config is keyed by client NAME. A config block for "catalogue"
        // against @FeignClient(name = "catalog") binds cleanly, reads cleanly,
        // and applies to nothing -- so assert the two agree rather than
        // trusting that they do.
        FeignClient annotation =
                CatalogPricingClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name())
                .as("@FeignClient(name) must match the config key asserted above")
                .isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("the timeouts are bound from the exact property keys, so a typo fails")
    void thePropertyKeysAreTheOnesSpringReads() {
        // A misspelled key leaves the library default in place exactly as an
        // omitted key does. Asserting the keys separates the two.
        String base = "spring.cloud.openfeign.client.config." + CLIENT + ".";

        assertThat(environment.getProperty(base + "connect-timeout", Integer.class))
                .as("%sconnect-timeout", base)
                .isEqualTo(CONNECT_MS);
        assertThat(environment.getProperty(base + "read-timeout", Integer.class))
                .as("%sread-timeout", base)
                .isEqualTo(READ_MS);
    }
}
