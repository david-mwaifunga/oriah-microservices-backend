package com.oriah.checkout.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Unit tests for the synchronous R4J decoration. We do not need a
 * Spring context: instantiate the registries with realistic configs,
 * hand them to the caller, and verify the supplier wiring.
 */
class ResilientFeignCallerTest {

    private final RetryConfig retryConfig = RetryConfig.<Object>custom()
            .maxAttempts(3)
            .retryExceptions(IOException.class, UncheckedIOException.class)
            .build();

    private final ResilientFeignCaller caller = new ResilientFeignCaller(
            CircuitBreakerRegistry.ofDefaults(),
            RetryRegistry.of(retryConfig),
            RateLimiterRegistry.of(RateLimiterConfig.ofDefaults()));

    @Test
    void successfulSupplierReturnsItsValue() {
        String result = caller.call("test1", () -> "ok", "fallback");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void failingSupplierReturnsTheFallback() {
        String result = caller.call("test2",
                () -> { throw new RuntimeException("boom"); },
                "fallback-value");
        assertThat(result).isEqualTo("fallback-value");
    }

    @Test
    void retryableExceptionIsRetriedUpToMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        String result = caller.call("test3", () -> {
            attempts.incrementAndGet();
            throw new UncheckedIOException(new IOException("transient"));
        }, "after-retries");

        assertThat(result).isEqualTo("after-retries");
        // maxAttempts = 3 means 1 initial + 2 retries = 3 calls total
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void retryRecoversIfALaterAttemptSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = caller.call("test4", () -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                throw new UncheckedIOException(new IOException("transient"));
            }
            return "third-try-charm";
        }, "fallback");

        assertThat(result).isEqualTo("third-try-charm");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void runsOnTheCallingThread() {
        // Critical for the SecurityContext ThreadLocal: the supplier
        // must execute on the same thread that invoked call(), NOT
        // on a ForkJoinPool worker (which is what Spring Cloud's
        // OpenFeign+R4J integration does and which originally broke
        // JWT propagation -- see ResilientFeignCaller class javadoc).
        Thread callingThread = Thread.currentThread();
        Thread[] supplierThread = new Thread[1];

        caller.call("test5", () -> {
            supplierThread[0] = Thread.currentThread();
            return "ok";
        }, "fallback");

        assertThat(supplierThread[0]).isSameAs(callingThread);
    }

    @Test
    void securityContextIsVisibleInsideTheSupplierOnEveryAttempt() {
        // Stronger than runsOnTheCallingThread above, and closer to the bug
        // that actually happened.
        //
        // "Same thread" is the MECHANISM; "the SecurityContext is readable
        // from inside the supplier" is the CONSEQUENCE that matters, because
        // that is what FeignTokenForwarder does -- it reads the credentials off
        // SecurityContextHolder to build the Authorization header. A future
        // refactor could keep the calling thread and still break propagation
        // (for instance by clearing the context between retries), and
        // runsOnTheCallingThread would not notice.
        //
        // The original failure presented as /checkout/preview returning all
        // zeros, and was only found from a Feign loggerLevel: full dump showing
        //   ---> GET http://cart/api/v1/carts/me
        // with NO Authorization header, on thread pool-2-thread-1 rather than
        // nio-8089-exec-N.
        //
        // Asserting on EVERY attempt is the point: a retry that loses the
        // context would make the first call succeed and the retry 401, which is
        // the hardest version of this bug to diagnose.
        Authentication authentication = new TestingAuthenticationToken(
                "maya", "token-value", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        AtomicInteger attempts = new AtomicInteger();
        List<Authentication> seenPerAttempt = new ArrayList<>();
        try {
            caller.call("test6", () -> {
                seenPerAttempt.add(SecurityContextHolder.getContext().getAuthentication());
                if (attempts.incrementAndGet() < 3) {
                    throw new UncheckedIOException(new IOException("transient"));
                }
                return "ok";
            }, "fallback");
        }
        finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(attempts.get())
                .as("the supplier should have been retried to the 3rd attempt")
                .isEqualTo(3);
        assertThat(seenPerAttempt)
                .as("every attempt, retries included, must see the caller's "
                        + "SecurityContext -- otherwise FeignTokenForwarder omits the "
                        + "Authorization header and the downstream 401s")
                .hasSize(3)
                .allSatisfy(seen -> assertThat(seen).isSameAs(authentication));
    }

    // ------------------------------------------- what a refusing service said

    private static feign.FeignException refusal(int status, byte[] body) {
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.POST,
                "http://shipping/api/v1/shipping/rates", java.util.Map.of(), new byte[0],
                java.nio.charset.StandardCharsets.UTF_8, new feign.RequestTemplate());
        return feign.FeignException.errorStatus("ShippingClient#rates(RateRequest)",
                feign.Response.builder().status(status).reason("refused").request(request)
                        .headers(java.util.Map.of()).body(body).build());
    }

    @Test
    void answeredProblemTypeReadsTheProblemBody() {
        byte[] body = "{\"type\":\"https://oriah.dev/problems/outside-delivery-area\",\"status\":422}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Wrapped, as a decorator would: the type is read off the cause chain.
        RuntimeException wrapped = new RuntimeException("decorated", refusal(422, body));

        assertThat(ResilientFeignCaller.answeredProblemType(wrapped))
                .contains(java.net.URI.create("https://oriah.dev/problems/outside-delivery-area"));
    }

    @Test
    void answeredProblemTypeIsEmptyForANonProblemBody() {
        assertThat(ResilientFeignCaller.answeredProblemType(refusal(422, new byte[0]))).isEmpty();
        assertThat(ResilientFeignCaller.answeredProblemType(
                refusal(500, "<html>gateway</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEmpty();
        assertThat(ResilientFeignCaller.answeredProblemType(new IllegalStateException("no answer at all"))).isEmpty();
    }
}