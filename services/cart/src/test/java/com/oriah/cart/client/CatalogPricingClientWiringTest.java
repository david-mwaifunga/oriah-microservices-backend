package com.oriah.cart.client;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.cart.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cart's first outbound HTTP dependency is wired, and wired to THROW.
 *
 * <h2>What this pins, and why it is worth a test of its own</h2>
 *
 * Three separate things have to be true before any pricing logic can be
 * trusted, and none of them is visible in the code that uses the client:
 *
 * <ol>
 *   <li>the client is a real bean -- without {@code @EnableFeignClients} the
 *       interface is just an interface, and the context starts perfectly
 *       happily without it;</li>
 *   <li>the circuit breaker is OFF, which is what makes Spring Cloud use
 *       {@code DefaultTargeter} so a failed call throws instead of being
 *       answered by something;</li>
 *   <li>the client declares no {@code fallback}, so there is nothing to answer
 *       with even if the breaker were switched on later.</li>
 * </ol>
 *
 * (2) and (3) are belt and braces for the same property deliberately. Catalog
 * is the cautionary example in this repo: it sets
 * {@code openfeign.circuitbreaker.enabled: true} and has a fallback, and the
 * combination is LIVE -- its {@code InventoryClientFallback} is what turns an
 * unreachable inventory-service into {@code Availability.UNKNOWN}. That is a
 * reasonable answer for "is this in stock" and an unacceptable one for "what
 * does this cost", so cart must not inherit it. Checkout's six fallback classes
 * are dead for exactly the configuration reason asserted here, and a single
 * property flip would resurrect them.
 *
 * <p>Pinning the property alone would not catch a {@code fallback} attribute
 * being added, and pinning the annotation alone would not catch the property
 * being flipped. Each check fails on its own mutation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class CatalogPricingClientWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the pricing client is a Feign proxy in the context")
    void clientIsWired() {
        CatalogPricingClient client = context.getBean(CatalogPricingClient.class);

        assertThat(client)
                .as("without @EnableFeignClients the context starts fine and this bean "
                        + "simply does not exist")
                .isNotNull();
        // Feign builds its clients with Proxy.newProxyInstance, so this
        // distinguishes a real Feign client from, say, a hand-written stub
        // somebody registered to make this test pass.
        assertThat(Proxy.isProxyClass(client.getClass()))
                .as("expected a Feign JDK dynamic proxy, got %s", client.getClass())
                .isTrue();
    }

    @Test
    @DisplayName("a failed pricing call throws: breaker off and no fallback declared")
    void failuresThrowRatherThanBeingAnswered() {
        // The property that decides it. With the breaker enabled, Spring Cloud
        // wraps the call and reads the fallback attribute; with it disabled,
        // DefaultTargeter is used and the exception propagates to the caller.
        assertThat(environment.getProperty("spring.cloud.openfeign.circuitbreaker.enabled"))
                .as("cart must follow checkout's config, not catalog's: a pricing call "
                        + "that fails has to throw, because every default answer to "
                        + "\"what does this cost\" is wrong")
                .isEqualTo("false");

        // And nothing to fall back TO, so flipping the property above cannot
        // quietly introduce an invented price.
        FeignClient annotation = CatalogPricingClient.class.getAnnotation(FeignClient.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("catalog");
        assertThat(annotation.fallback())
                .as("a fallback would have to invent a price; void.class means there is none")
                .isEqualTo(void.class);
        assertThat(annotation.fallbackFactory())
                .as("fallbackFactory is the other way to supply one")
                .isEqualTo(void.class);
        assertThat(annotation.url())
                .as("no hardcoded url: resolution goes through Eureka by service name")
                .isEmpty();
    }
}
