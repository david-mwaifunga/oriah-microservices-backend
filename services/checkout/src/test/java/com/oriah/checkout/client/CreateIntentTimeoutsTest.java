package com.oriah.checkout.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payment.createIntent waits for payment on settings of its own, shorter than
 * the ones confirm and every other call share.
 *
 * <h2>Why</h2>
 *
 * createIntent is repeated when its reply is lost (it is idempotent: payment
 * hands a repeated create the intent it already made). On the shared settings
 * -- 5 s to connect, 15 s to answer -- three attempts could take
 * 3 x 20 s + 2 x 0.5 s = 61 s. But payment's create is itself bounded: it asks
 * order whose order it is, giving order at most 1 s to connect and 3 s to
 * answer (payment's own CreateIntentBudgetTest), then makes one short database
 * write. So 1 s to connect and 6 s to answer is enough for payment's worst
 * create with 2 s to spare, and three attempts take at most
 * 3 x (1 s + 6 s) + 2 x 0.5 s = 22 s -- about what ONE attempt could take before.
 *
 * <p>Spring Cloud keys a Feign client's settings by its {@code contextId}, so
 * createIntent lives on its own client, {@link PaymentCreateClient}, whose
 * contextId is {@code payment-create} -- the same name as its resilience
 * instance. That the key really reaches the call (and not just binds) is shown
 * by {@code ConfirmOverRealFeignTest.createIntentWaitsOnlyAsLongAsItsOwnClientAllows}.
 *
 * <p>Payment reads these same numbers from this file in its
 * CreateIntentBudgetTest, so shortening them can turn payment's build red --
 * on purpose.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"
})
class CreateIntentTimeoutsTest {

    /** The contextId of createIntent's own Feign client, and its resilience instance's name. */
    private static final String CLIENT = "payment-create";

    @Autowired
    private FeignClientProperties feignClientProperties;

    @Test
    @DisplayName("createIntent's own client, 'payment-create': 1 s to connect, 6 s to answer")
    void createIntentHasItsOwnTimeouts() {
        FeignClientProperties.FeignClientConfiguration config = feignClientProperties.getConfig().get(CLIENT);

        assertThat(config)
                .as("no '%s' entry in spring.cloud.openfeign.client.config, so createIntent waits as long "
                        + "as confirm does (the default: 5 s + 15 s per attempt)", CLIENT)
                .isNotNull();
        assertThat(config.getConnectTimeout()).as("%s connect timeout", CLIENT).isEqualTo(1_000);
        assertThat(config.getReadTimeout()).as("%s read timeout", CLIENT).isEqualTo(6_000);
    }
}
