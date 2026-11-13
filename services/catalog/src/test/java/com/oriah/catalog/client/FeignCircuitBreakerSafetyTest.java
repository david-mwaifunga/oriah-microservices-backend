package com.oriah.catalog.client;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import feign.RequestInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the invariant that makes catalog's thread-hopping Feign resilience
 * safe — an invariant that was load-bearing and, until this test, undocumented.
 *
 * <h2>The asymmetry</h2>
 *
 * <p>catalog sets {@code spring.cloud.openfeign.circuitbreaker.enabled: true}
 * and configures a Resilience4j {@code timelimiter}. checkout sets the same
 * flag to {@code false} and refuses {@code timelimiter} and {@code bulkhead}
 * outright. Both are correct, for a reason invisible in either file alone.
 *
 * <p>Spring Cloud's OpenFeign + Resilience4j integration dispatches each call
 * to a pool worker, and {@code timelimiter} does the same. checkout reads the
 * Authorization header off {@code SecurityContextHolder}, a {@link ThreadLocal}:
 * on a pool worker it is empty, the header is silently omitted, and every
 * downstream returns 401. That is the deepest bug this codebase has had. It
 * presented as {@code /checkout/preview} returning all zeros, and was found
 * only from a Feign {@code loggerLevel: full} dump showing
 * {@code ---> GET http://cart/api/v1/carts/me} with no Authorization header, on
 * thread {@code pool-2-thread-1} instead of {@code nio-8089-exec-N}.
 *
 * <p>catalog is immune only because its single downstream call needs no token:
 * it forwards none, and inventory permits {@code GET /api/v1/inventory/**}
 * anonymously.
 *
 * <h2>What this test is for</h2>
 *
 * <p>If catalog ever gains a {@link RequestInterceptor} that forwards a JWT, or
 * inventory's read becomes authenticated, the thread hop starts dropping the
 * token. The failure would be silent and specifically nasty: availability
 * enrichment degrades to the fallback, so every product page renders without a
 * stock badge rather than erroring, and nothing in the logs says 401.
 *
 * <p>If you are reading this because the test went red after adding a
 * token-forwarding interceptor: set
 * {@code spring.cloud.openfeign.circuitbreaker.enabled: false}, remove the
 * {@code timelimiter}, and wrap the call the way
 * {@code com.oriah.checkout.resilience.ResilientFeignCaller} does — composing
 * the decorators by hand and invoking them on the calling thread.
 *
 * <p>Deliberately context-free. Booting catalog's context would drag in JPA and
 * a database for assertions that are answerable by classpath scanning and
 * reflection, and a guard that takes 90 seconds is a guard people switch off.
 */
class FeignCircuitBreakerSafetyTest {

    private static final String CATALOG_BASE_PACKAGE = "com.oriah.catalog";

    @Test
    @DisplayName("catalog declares no Feign RequestInterceptor, so there is no token to drop")
    void noTokenForwardingInterceptorExists() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(RequestInterceptor.class));

        Set<BeanDefinition> interceptors = scanner.findCandidateComponents(CATALOG_BASE_PACKAGE);

        assertThat(interceptors.stream().map(BeanDefinition::getBeanClassName).toList())
                .as("catalog runs with spring.cloud.openfeign.circuitbreaker.enabled=true and a "
                        + "resilience4j timelimiter, both of which move the call to a pool thread. "
                        + "A RequestInterceptor reading SecurityContextHolder finds it EMPTY "
                        + "there and omits the Authorization header silently -- product pages "
                        + "lose their stock badge via the fallback, with no 401 anywhere in the "
                        + "logs. If you are adding one: disable the Feign circuit breaker, drop "
                        + "the timelimiter, and decorate on the calling thread the way "
                        + "checkout's ResilientFeignCaller does.")
                .isEmpty();
    }

    @Test
    @DisplayName("the stock badge's Feign client targets inventory's anonymous read path")
    void feignClientCallsOnlyThePublicInventoryPath() {
        // The other half of the invariant. inventory permits
        // GET /api/v1/inventory/** with no token; /api/v1/admin/inventory/**
        // requires INVENTORY_ADMIN. Calling an admin path from here would need
        // a JWT, and so would hit the thread-hop problem.
        FeignClient annotation = InventoryClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation).as("InventoryClient must stay a @FeignClient").isNotNull();
        assertThat(annotation.name()).isEqualTo("inventory");

        for (Method method : InventoryClient.class.getDeclaredMethods()) {
            String mappings = Arrays.stream(method.getAnnotations())
                    .map(Object::toString)
                    .reduce("", String::concat);

            assertThat(mappings)
                    .as("%s must call inventory's PUBLIC read path. An /admin/ path requires "
                            + "INVENTORY_ADMIN, which means forwarding a JWT, which is unsafe "
                            + "while the Feign circuit breaker and timelimiter are enabled here",
                            method.getName())
                    .doesNotContain("/admin/");
        }
    }

    /**
     * Phase 6 added a SECOND client that does need the caller's token: the price check asks inventory what stock cost, as the
     * person pricing. It is safe only because it differs from the first in every way that matters, so each way is pinned here,
     * and a client that goes back to sharing the first one's setup fails one of these. Merely finding no RequestInterceptor above
     * would not catch that: the danger is a token-carrying call on a thread-hopping, degrading client, not an interceptor.
     */
    @Test
    @DisplayName("the cost client passes the caller's token as an explicit parameter on every call, never from a thread-bound context")
    void costClientPassesTheTokenExplicitly() {
        for (Method method : InventoryCostClient.class.getDeclaredMethods()) {
            boolean hasAuthorizationHeader = Arrays.stream(method.getParameters())
                    .map(p -> p.getAnnotation(org.springframework.web.bind.annotation.RequestHeader.class))
                    .anyMatch(h -> h != null && org.springframework.http.HttpHeaders.AUTHORIZATION.equalsIgnoreCase(
                            h.value().isEmpty() ? h.name() : h.value()));

            assertThat(hasAuthorizationHeader)
                    .as("%s must take the Authorization header as a parameter: the caller reads it on the request thread", method.getName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the cost client has no fallback and does not turn a 404 into an answer: it must never degrade into 'no cost known'")
    void costClientNeverDegrades() {
        FeignClient annotation = InventoryCostClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation).as("InventoryCostClient must stay a @FeignClient").isNotNull();
        assertThat(annotation.fallback()).as("a fallback answering 'nothing held' would let a price below cost through unseen").isEqualTo(void.class);
        assertThat(annotation.fallbackFactory()).isEqualTo(void.class);
        assertThat(annotation.dismiss404()).as("a 404 from the cost read is a fault, not 'no stock'").isFalse();
        assertThat(annotation.contextId()).as("its own context, so the stock badge's breaker and fallback never apply to it").isEqualTo("inventory-costs");
    }

    @Test
    @DisplayName("the cost client's configuration declares a plain prototype Feign builder, which is what keeps it out of the thread-hopping breaker")
    void costClientIsKeptOutOfTheBreaker() throws Exception {
        FeignClient annotation = InventoryCostClient.class.getAnnotation(FeignClient.class);
        assertThat(annotation.configuration()).containsExactly(InventoryCostClientConfiguration.class);

        Method[] beans = InventoryCostClientConfiguration.class.getDeclaredMethods();
        Method builder = Arrays.stream(beans).filter(m -> feign.Feign.Builder.class.equals(m.getReturnType())).findFirst().orElse(null);

        assertThat(builder).as("a Feign.Builder bean in the cost client's configuration").isNotNull();
        assertThat(builder.getAnnotation(org.springframework.context.annotation.Scope.class).value()).isEqualTo("prototype");
        assertThat(InventoryCostClientConfiguration.class.getAnnotation(org.springframework.context.annotation.Configuration.class))
                .as("not a @Configuration, or component scanning would apply it to every Feign client in the service")
                .isNull();
    }

    @Test
    @DisplayName("no circuit breaker and no time limiter is configured for the cost client by name: its timeouts are plain Feign ones")
    void costClientHasNoLimiterConfiguredByName() {
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yaml"));
        java.util.Properties properties = java.util.Objects.requireNonNull(yaml.getObject());

        assertThat(properties.stringPropertyNames())
                .filteredOn(key -> key.startsWith("resilience4j.") && key.contains("inventory-costs"))
                .as("a time limiter dispatches the call to another thread; the cost client is timed by Feign's own timeouts")
                .isEmpty();
        assertThat(properties.getProperty("spring.cloud.openfeign.client.config.inventory-costs.read-timeout")).isNotBlank();
        assertThat(properties.getProperty("spring.cloud.openfeign.client.config.inventory-costs.connect-timeout")).isNotBlank();
    }

    /**
     * Phase 7 added a THIRD client: promotion's in-force read, which catalog asks to price every piece. It is anonymous (no token to drop),
     * but it must never degrade: a fallback answering "no promotion in force" would charge the list price during a promotion and quote it
     * to the customer. So it is kept out of the breaker and the time limiter like the cost client, and a promotion service that cannot
     * answer is a 503.
     */
    @Test
    @DisplayName("the promotion client is anonymous: no Authorization parameter, no token to forward, and only promotion's internal read")
    void promotionClientIsAnonymousAndInternal() {
        for (Method method : PromotionClient.class.getDeclaredMethods()) {
            boolean takesAuthorization = Arrays.stream(method.getParameters())
                    .map(p -> p.getAnnotation(org.springframework.web.bind.annotation.RequestHeader.class))
                    .anyMatch(h -> h != null);
            assertThat(takesAuthorization).as("%s is anonymous: catalog prices for the storefront and has no token to forward", method.getName()).isFalse();

            String mappings = Arrays.stream(method.getAnnotations()).map(Object::toString).reduce("", String::concat);
            assertThat(mappings).as("%s reads promotion's internal list, which the gateway never routes", method.getName())
                    .contains("/internal/promotions/").doesNotContain("/admin/");
        }
    }

    @Test
    @DisplayName("the promotion client has no fallback and does not turn a 404 into an answer: it must never degrade into 'no promotion in force'")
    void promotionClientNeverDegrades() {
        FeignClient annotation = PromotionClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation).as("PromotionClient must stay a @FeignClient").isNotNull();
        assertThat(annotation.name()).isEqualTo("promotion");
        assertThat(annotation.fallback()).as("a fallback answering 'no promotion' would charge the list price during a promotion").isEqualTo(void.class);
        assertThat(annotation.fallbackFactory()).isEqualTo(void.class);
        assertThat(annotation.dismiss404()).isFalse();
        assertThat(annotation.contextId()).as("its own context, so the stock badge's breaker and fallback never apply to it").isEqualTo("promotion-in-force");
    }

    @Test
    @DisplayName("the promotion client's configuration declares a plain prototype Feign builder, which keeps it out of the breaker and its time limiter")
    void promotionClientIsKeptOutOfTheBreaker() {
        FeignClient annotation = PromotionClient.class.getAnnotation(FeignClient.class);
        assertThat(annotation.configuration()).containsExactly(PromotionClientConfiguration.class);

        Method builder = Arrays.stream(PromotionClientConfiguration.class.getDeclaredMethods())
                .filter(m -> feign.Feign.Builder.class.equals(m.getReturnType())).findFirst().orElse(null);

        assertThat(builder).as("a Feign.Builder bean in the promotion client's configuration").isNotNull();
        assertThat(builder.getAnnotation(org.springframework.context.annotation.Scope.class).value()).isEqualTo("prototype");
        assertThat(PromotionClientConfiguration.class.getAnnotation(org.springframework.context.annotation.Configuration.class))
                .as("not a @Configuration, or component scanning would apply it to every Feign client in the service").isNull();
    }

    @Test
    @DisplayName("promotion's timeouts are plain Feign ones (half a second to connect, a second and a half to read) and no limiter is configured for it by name")
    void promotionClientTimeouts() {
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yaml"));
        java.util.Properties properties = java.util.Objects.requireNonNull(yaml.getObject());

        assertThat(properties.stringPropertyNames())
                .filteredOn(key -> key.startsWith("resilience4j.") && key.contains("promotion"))
                .as("a time limiter dispatches the call to another thread; the promotion client is timed by Feign's own timeouts").isEmpty();
        assertThat(properties.getProperty("spring.cloud.openfeign.client.config.promotion-in-force.connect-timeout")).isEqualTo("500");
        assertThat(properties.getProperty("spring.cloud.openfeign.client.config.promotion-in-force.read-timeout")).isEqualTo("1500");
    }

    @Test
    @DisplayName("a fallback is declared, so a tripped breaker degrades rather than failing the page")
    void fallbackIsDeclared() {
        // Availability enrichment is decoration on a product page. With no
        // fallback a tripped breaker propagates and fails the whole product
        // read, turning a missing stock badge into a broken page.
        FeignClient annotation = InventoryClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation.fallback())
                .as("InventoryClient must name a fallback bean")
                .isEqualTo(InventoryClientFallback.class);
    }
}
