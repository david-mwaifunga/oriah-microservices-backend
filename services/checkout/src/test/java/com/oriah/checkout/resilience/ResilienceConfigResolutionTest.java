package com.oriah.checkout.resilience;

import java.util.stream.Stream;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.test.context.TestPropertySource;

import com.oriah.checkout.client.OrderClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves checkout still starts, and still has working resilience settings, when
 * the Config Server is unavailable.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The shared Resilience4j numbers moved to
 * {@code config-repo/application.yaml} as
 * {@code resilience4j.{circuitbreaker,retry,ratelimiter}.configs.default}, and
 * this service's instances were reduced to
 * {@code <name>: { base-config: default }}.
 *
 * <p>That raises a question the move itself does not answer. checkout imports
 * configuration with {@code spring.config.import:
 * "optional:configserver:..."} — <em>optional</em>, so it is designed to boot
 * when the Config Server is down. If {@code configs.default} lives only in
 * config-repo, then in that situation every instance references a base config
 * that does not exist. Two possible outcomes, and they are very different:
 *
 * <ul>
 *   <li>a loud {@code ConfigurationNotFoundException} at startup — bad, because
 *       centralizing shared values would have converted an optional dependency
 *       into a hard one, which is a <em>reduction</em> in resilience from a
 *       change made in the name of resilience; or</li>
 *   <li>a silent fall back to Resilience4j's own built-in defaults — worse,
 *       because those differ materially (sliding window 100 rather than 10,
 *       wait-in-open 60s rather than 30s) and nothing would say so.</li>
 * </ul>
 *
 * <p>So the behaviour is pinned here rather than reasoned about. The test runs
 * with {@code spring.cloud.config.enabled=false}, which is exactly the
 * Config-Server-is-down case.
 *
 * <p>The same file also pins what each of the five instances (cart, catalog,
 * customer, shipping, order) resolves to, what the one retry rule
 * ({@link RetryForAnyCall}) repeats and refuses to repeat -- judged on
 * failures a real Feign client raised ({@link RealFeignFailures}) -- that a 4xx
 * is neither retried nor counted against a breaker, and how long a call may
 * take: 5s to connect and 15s to answer, and 20s for order's placement.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // The scenario under test: no Config Server, so nothing supplies
        // resilience4j.*.configs.default from config-repo.
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"
})
class ResilienceConfigResolutionTest {

    /**
     * Every resilience instance checkout uses: one per Feign client. (Since
     * Phase 7 checkout calls no payment service, so there is no payment
     * instance, nor one for charging a card.)
     */
    private static final String[] CLIENTS = {"cart", "catalog", "customer", "shipping", "order"};

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private org.springframework.core.env.Environment environment;

    /** The instances as Resilience4j itself bound them from application.yaml. */
    @Autowired
    private io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerProperties
            circuitBreakerProperties;

    @Autowired
    private io.github.resilience4j.springboot3.retry.autoconfigure.RetryProperties retryProperties;

    @Autowired
    private io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimiterProperties
            rateLimiterProperties;

    /** The Feign clients' waits as Spring Cloud binds them from application.yaml. */
    @Autowired
    private FeignClientProperties feignClientProperties;

    @Test
    @DisplayName("the context starts at all without the Config Server")
    void contextStartsWithoutConfigServer() {
        // If base-config resolution were fatal, this test would never reach its
        // body -- the failure would be a context-load error.
        assertThat(circuitBreakerRegistry).isNotNull();
        assertThat(retryRegistry).isNotNull();
        assertThat(rateLimiterRegistry).isNotNull();
    }

    @ParameterizedTest(name = "{0} has a circuit breaker")
    @DisplayName("every Feign client still gets its own breaker instance")
    @ValueSource(strings = {"cart", "catalog", "customer", "shipping", "order"})
    void everyClientHasItsOwnBreaker(String client) {
        // One instance per client, so that when a downstream is unhealthy every
        // call from this orchestrator to it trips the SAME circuit
        // (application.yaml, "Instances").
        assertThat(circuitBreakerRegistry.circuitBreaker(client)).isNotNull();
        assertThat(retryRegistry.retry(client)).isNotNull();
        assertThat(rateLimiterRegistry.rateLimiter(client)).isNotNull();
    }

    @ParameterizedTest(name = "{0} is registered in all three blocks")
    @DisplayName("every instance checkout calls is REGISTERED at startup, not created on first use")
    @ValueSource(strings = {"cart", "catalog", "customer", "shipping", "order"})
    void everyInstanceIsRegistered(String instance) {
        // Read from the CONFIGURATION, not the registries: a registry hands out
        // an instance for any name it is asked for, so "the registry has it"
        // is satisfied by the other tests in this class asking first -- an
        // assertion on the registry alone passed with an instance deleted from
        // the yaml. What matters is that application.yaml names the instance
        // in all three blocks.
        for (String block : new String[] {"circuitbreaker", "retry", "ratelimiter"}) {
            assertThat(environment.getProperty("resilience4j." + block + ".instances." + instance + ".base-config"))
                    .as("resilience4j.%s.instances.%s is not configured", block, instance)
                    .isEqualTo("default");
        }
    }

    @ParameterizedTest(name = "HTTP {0} is retried")
    @DisplayName("an outage is retried: no answer, 502, 503 and 504")
    @ValueSource(ints = {502, 503, 504})
    void anOutageIsRetried(int status) {
        var config = retryRegistry.retry("order").getRetryConfig();

        assertThat(config.getExceptionPredicate().test(serverError(status))).isTrue();
        // "No answer" is a connection that could not be opened, as Feign
        // really reports it. This line used to test a bare
        // SocketTimeoutException("read timed out"): Feign never throws one (it
        // wraps it in feign.RetryableException), so it only proved that the
        // dead java.io.IOException entry matched an exception that never
        // arrives. And a read timeout is now deliberately NOT retried -- see
        // aRequestThatMayHaveArrivedIsNotRetried.
        assertThat(config.getExceptionPredicate().test(refused))
                .as("a refused connection, as Feign throws it (%s)", refused)
                .isTrue();
    }

    // ----------------------------------------------- real failures, as Feign throws them

    /**
     * Every resilience instance checkout uses -- all on the one retry rule
     * ({@link RetryForAnyCall}): none repeats a request that may have
     * arrived, all repeat one that never did, and all repeat an answered 502,
     * 503 or 504 that does not ask for later.
     */
    static Stream<String> everyInstance() {
        return Stream.of(CLIENTS);
    }

    private static RealFeignFailures failures;
    private static RuntimeException refused;
    private static RuntimeException readTimeout;
    private static RuntimeException reset;
    private static RuntimeException closedWithoutAnAnswer;

    /**
     * Made once: each is a real call, and the predicates under test only read
     * them. Only failures this machine can make by itself (a closed port, a
     * local server): a connect timeout and an unknown host need the outside
     * network, so the tests that need them ask {@link RealFeignFailures} for
     * them one by one, and are skipped where the network cannot make them.
     * Asked for here, one odd network would fail every test in the class.
     */
    @BeforeAll
    static void makeRealFailures() {
        failures = new RealFeignFailures();
        refused = RealFeignFailures.refused();
        readTimeout = failures.readTimeout();
        reset = failures.resetAfterTheRequestWentOut();
        closedWithoutAnAnswer = failures.closedWithoutAnAnswer();
    }

    @AfterAll
    static void stopTheServer() {
        failures.close();
    }

    private java.util.function.Predicate<Throwable> retries(String instance) {
        return retryRegistry.retry(instance).getRetryConfig().getExceptionPredicate();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a connection that was never opened is retried: refused")
    @MethodSource("everyInstance")
    void aConnectionThatWasNeverOpenedIsRetried(String instance) {
        // The request never left checkout, so a second attempt cannot make a
        // second order. Feign reports it as feign.RetryableException (status
        // -1) with the network's own exception as the cause -- never as an
        // IOException, which is all the old retry-exceptions list could match.
        assertThat(retries(instance).test(refused))
                .as("%s: a refused connection (%s) must be retried", instance, refused)
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a host that does not resolve is retried (skipped where this network's DNS cannot show it)")
    @MethodSource("everyInstance")
    void aHostThatDoesNotResolveIsRetried(String instance) {
        // Never connected either. Asks this machine's DNS, so it is made here,
        // lazily, and skipped -- not failed -- on a network whose DNS answers
        // for names that do not exist.
        RuntimeException unknownHost = RealFeignFailures.unknownHost();

        assertThat(retries(instance).test(unknownHost))
                .as("%s: a host that does not resolve (%s) must be retried", instance, unknownHost)
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a request that may have arrived is NOT retried: a read timeout, a reset, a silent close")
    @MethodSource("everyInstance")
    void aRequestThatMayHaveArrivedIsNotRetried(String instance) {
        // order.place is a POST that is not idempotent: if the request arrived
        // and only the reply was lost, a second attempt can save a second
        // order. No instance retries these.
        assertThat(retries(instance).test(readTimeout))
                .as("%s: a read timeout (%s) must not be retried", instance, readTimeout)
                .isFalse();
        assertThat(retries(instance).test(reset))
                .as("%s: a reset after the request went out (%s) must not be retried", instance, reset)
                .isFalse();
        assertThat(retries(instance).test(closedWithoutAnAnswer))
                .as("%s: a connection closed with no answer (%s) must not be retried",
                        instance, closedWithoutAnAnswer)
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a connect timeout is NOT retried: through Feign it cannot be told apart from a read timeout "
            + "(skipped where this network cannot make one)")
    @MethodSource("everyInstance")
    void aConnectTimeoutIsNotRetried(String instance) {
        // Feign's HttpURLConnection client reports both timeouts as the same
        // class, SocketTimeoutException, wrapped in the same RetryableException;
        // only the JDK's message text differs ("Connect timed out" / "Read
        // timed out"), and a message is not a contract. Told apart wrongly, a
        // read timeout would be retried into a second order, so neither is.
        //
        // A connect timeout needs a network that drops a connection in
        // silence. RealFeignFailures makes it here, on demand, checks that it
        // really is a connect timeout, and SKIPS this test (saying why) where
        // the network refuses or answers instead -- it used to make it for the
        // whole class and fail here on such a network, offline or on a VPN.
        RuntimeException connectTimeout = RealFeignFailures.connectTimeout();

        assertThat(RealFeignFailures.rootCause(connectTimeout).getClass())
                .as("the connect timeout's type is the read timeout's: nothing but the message differs")
                .isEqualTo(RealFeignFailures.rootCause(readTimeout).getClass());
        assertThat(retries(instance).test(connectTimeout))
                .as("%s: a connect timeout (%s) must not be retried", instance, connectTimeout)
                .isFalse();
    }

    @ParameterizedTest(name = "{0}: HTTP {1}")
    @DisplayName("an outage answered by a real server without Retry-After is retried: 502, 503 and 504, on every instance")
    @MethodSource("instancesAndOutages")
    void anAnsweredOutageIsRetried(String instance, int status) {
        RuntimeException plain = failures.answered(status, null);

        assertThat(retries(instance).test(plain))
                .as("%s: %s must be retried", instance, plain)
                .isTrue();
    }

    @ParameterizedTest(name = "{0}: HTTP {1}")
    @DisplayName("an outage that asks to be tried LATER (Retry-After) is not repeated, on any rule")
    @MethodSource("instancesAndOutages")
    void anOutageThatAsksForLaterIsNotRepeated(String instance, int status) {
        // Order sends Retry-After with a 503 only AFTER waiting on its own
        // dependencies (when customer, its token, shipping, catalog or
        // inventory let it down). Repeating that answer half a second later
        // waits the whole time again, up to three times -- order.place's slow
        // 503 grew from about 15 s to about 46 s, past the storefront's 30 s --
        // and leans on a service that has just said it is struggling. So it is
        // not repeated: the shopper gets checkout's own 503, with its own
        // Retry-After. (Feign throws these as feign.RetryableException
        // carrying the status; the retry list before RetryForAnyCall never
        // matched that, so they were not repeated then either.)
        RuntimeException withRetryAfter = failures.answered(status, "5");

        assertThat(retries(instance).test(withRetryAfter))
                .as("%s: %s (with Retry-After) must not be repeated", instance, withRetryAfter)
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a 503 whose Retry-After Feign cannot read ('soon') still asks for later: not repeated, on any rule")
    @MethodSource("everyInstance")
    void anUnreadableRetryAfterStillAsksForLater(String instance) {
        // Feign turns a Retry-After it can read -- seconds, or an HTTP date --
        // into feign.RetryableException. One it cannot read it drops, and
        // throws the plain FeignException$ServiceUnavailable instead. But the
        // service still said "not now", so the header itself is what
        // NetworkFailures.askedToComeBackLater reads, not Feign's exception
        // class. Every other test here sends "5", which Feign can read, so
        // only this one would notice the rule looking at the class instead.
        RuntimeException unreadable = failures.answered(503, "soon");

        assertThat(unreadable)
                .as("for a Retry-After it cannot read, Feign throws a plain FeignException (%s)", unreadable)
                .isNotInstanceOf(feign.RetryableException.class);
        assertThat(retries(instance).test(unreadable))
                .as("%s: %s (Retry-After: soon) must not be repeated", instance, unreadable)
                .isFalse();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> instancesAndOutages() {
        return everyInstance().flatMap(instance -> Stream.of(502, 503, 504)
                .map(status -> org.junit.jupiter.params.provider.Arguments.of(instance, status)));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("an answered 500 or 4xx refusal is never retried, on any instance: it comes back the same every time")
    @MethodSource("everyInstance")
    void anAnswerThatIsNotAnOutageIsNeverRetried(String instance) {
        // A refusal or the service's own failure comes back the same on every
        // attempt -- and order.place is a POST, so a 500 that follows a saved
        // order must not become a second one.
        RuntimeException internal = failures.answered(500, null);
        RuntimeException notFound = failures.answered(404, null);
        RuntimeException conflict = failures.answered(409, null);

        assertThat(retries(instance).test(internal)).as("%s: %s", instance, internal).isFalse();
        assertThat(retries(instance).test(notFound)).as("%s: %s", instance, notFound).isFalse();
        assertThat(retries(instance).test(conflict)).as("%s: %s", instance, conflict).isFalse();
    }

    @Test
    @DisplayName("an unregistered name is no error: it silently gets configs.default, so the yaml is what must name each instance")
    void anUnregisteredNameSilentlyGetsTheDefaultRetry() {
        // Why every instance must be NAMED in application.yaml: a misspelt name
        // starts without a word and quietly gets configs.default instead of
        // any setting of its own. everyInstanceIsRegistered reads the yaml for
        // exactly this reason.
        var misspelt = retries("ordr");

        assertThat(misspelt.test(readTimeout)).as("the default rule: a read timeout is not retried").isFalse();
        assertThat(misspelt.test(refused)).as("the default rule: a refused connection is").isTrue();
    }

    @Test
    @DisplayName("the instances configured are exactly these five, in all three blocks: nothing retired is left, nothing new is unpinned")
    void theConfiguredInstancesAreExactlyTheFive() {
        // Phase 7 retired payment, payment-create and order-pay: checkout no
        // longer calls payment. An instance left in the yaml is dead settings
        // nobody notices; one added without being named here has no test for
        // what it resolves to. (The registries cannot say: they hand out an
        // instance for any name.)
        assertThat(circuitBreakerProperties.getInstances().keySet())
                .as("resilience4j.circuitbreaker.instances")
                .containsExactlyInAnyOrder(CLIENTS);
        assertThat(retryProperties.getInstances().keySet())
                .as("resilience4j.retry.instances")
                .containsExactlyInAnyOrder(CLIENTS);
        assertThat(rateLimiterProperties.getInstances().keySet())
                .as("resilience4j.ratelimiter.instances")
                .containsExactlyInAnyOrder(CLIENTS);
    }

    // ------------------------------------------------- how long a call may take

    @Test
    @DisplayName("every Feign client waits 5s to connect and 15s to answer, as the default")
    void theDefaultWaitsAreFiveAndFifteenSeconds() {
        // The cart, catalog, customer and shipping reads, each of which
        // answers in milliseconds when warm. A misspelt key leaves feign-core's
        // own 10s/60s in place, which application.yaml's worst-case table
        // (and the storefront's 30s) are not worked out for.
        var config = feignClientProperties.getConfig().get(feignClientProperties.getDefaultConfig());

        assertThat(config).as("the default Feign client config").isNotNull();
        assertThat(config.getConnectTimeout()).as("default connect timeout, ms").isEqualTo(5_000);
        assertThat(config.getReadTimeout()).as("default read timeout, ms").isEqualTo(15_000);
    }

    @Test
    @DisplayName("order's client waits 20s to answer a placement, longer than the default's 15s")
    void ordersClientWaitsTwentySecondsToAnswer() {
        // Placing an order is five calls one after another inside order
        // (customer, catalog, its own token, shipping, inventory's hold) and
        // then its own save: 17.5s at worst, which does not fit in 15s. Keyed
        // by the @FeignClient name; only the read timeout is its own, the
        // connect timeout is the default's.
        assertThat(OrderClient.class.getAnnotation(FeignClient.class).name())
                .as("the name application.yaml's entry must carry")
                .isEqualTo("order");
        var order = feignClientProperties.getConfig().get("order");

        assertThat(order).as("an 'order' entry in spring.cloud.openfeign.client.config").isNotNull();
        assertThat(order.getReadTimeout()).as("order read timeout, ms").isEqualTo(20_000);
    }

    @Test
    @DisplayName("no other Feign client has a wait of its own: payment's retired entry is gone")
    void noClientButOrderHasAWaitOfItsOwn() {
        assertThat(feignClientProperties.getConfig().keySet())
                .as("spring.cloud.openfeign.client.config entries")
                .containsExactlyInAnyOrder(feignClientProperties.getDefaultConfig(), "order");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("every instance is bound by Resilience4j in all three blocks, and resolves to the configured values")
    @MethodSource("everyInstance")
    void everyInstanceResolvesToTheConfiguredValues(String instance) {
        // ResilientFeignCaller looks each instance up by name in all three
        // registries. The VALUES alone cannot tell a registered instance from
        // a missing one: a name application.yaml does not declare is handed
        // configs.default, which has exactly these values (see
        // anUnregisteredNameSilentlyGetsTheDefaultRetry) -- this test once
        // stayed green with an instance deleted from two of the blocks.
        // So first: Resilience4j itself must have bound the instance's own
        // settings from application.yaml, in each block. Only then are the
        // values worth reading. (What the values do rule out is Resilience4j's
        // built-in defaults: a breaker window of 100 that waits 60s, a limiter
        // that refreshes every 500ns and waits 5s for a permit.)
        assertThat(circuitBreakerProperties.getInstances().get(instance))
                .as("resilience4j.circuitbreaker.instances.%s is not declared, so it runs on "
                        + "configs.default without a word", instance)
                .isNotNull()
                .extracting(p -> p.getBaseConfig()).isEqualTo("default");
        assertThat(retryProperties.getInstances().get(instance))
                .as("resilience4j.retry.instances.%s is not declared, so it runs on configs.default "
                        + "without a word", instance)
                .isNotNull()
                .extracting(p -> p.getBaseConfig()).isEqualTo("default");
        assertThat(rateLimiterProperties.getInstances().get(instance))
                .as("resilience4j.ratelimiter.instances.%s is not declared, so it runs on "
                        + "configs.default without a word", instance)
                .isNotNull()
                .extracting(p -> p.getBaseConfig()).isEqualTo("default");

        var breaker = circuitBreakerRegistry.circuitBreaker(instance).getCircuitBreakerConfig();
        assertThat(breaker.getSlidingWindowSize()).as("%s breaker window", instance).isEqualTo(10);
        assertThat(breaker.getFailureRateThreshold()).as("%s failure rate", instance).isEqualTo(50f);
        assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1)).as("%s wait in open", instance)
                .isEqualTo(30_000L);

        var retry = retryRegistry.retry(instance).getRetryConfig();
        assertThat(retry.getMaxAttempts()).as("%s attempts", instance).isEqualTo(3);
        assertThat(retry.getIntervalBiFunction().apply(1, null)).as("%s wait between attempts", instance)
                .isEqualTo(500L);

        var limiter = rateLimiterRegistry.rateLimiter(instance).getRateLimiterConfig();
        assertThat(limiter.getLimitForPeriod()).as("%s permits per period", instance).isEqualTo(50);
        assertThat(limiter.getLimitRefreshPeriod()).as("%s period", instance)
                .isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(limiter.getTimeoutDuration()).as("%s wait for a permit", instance)
                .isEqualTo(java.time.Duration.ofMillis(100));
    }

    @Test
    @DisplayName("a 500 is NOT retried: it is the downstream's own failure, and order.place is a POST")
    void anInternalServerErrorIsNotRetried() {
        // order answers 500 on purpose for its own misconfiguration (a wrong
        // client secret, shipping refusing it), and a 500 can come AFTER the
        // order was saved -- so retrying order.place risks a second order, and
        // three attempts with backoff only delay the same answer.
        var config = retryRegistry.retry("order").getRetryConfig();

        assertThat(config.getExceptionPredicate().test(serverError(500))).isFalse();
    }

    private static feign.FeignException serverError(int status) {
        return feign.FeignException.errorStatus("OrderClient#place(String,PlaceOrderRequest)",
                feign.Response.builder().status(status).reason("x")
                        .request(feign.Request.create(feign.Request.HttpMethod.POST, "http://order/api/v1/orders",
                                java.util.Map.of(), null, null, null))
                        .headers(java.util.Map.of()).build());
    }

    @Test
    @DisplayName("breakers are not silently left on Resilience4j's built-in defaults")
    void breakersDoNotSilentlyFallBackToLibraryDefaults() {
        // This is the assertion that would catch the dangerous outcome. The
        // library's own defaults are slidingWindowSize=100 and
        // waitDurationInOpenState=60s; ours are 10 and 30s. If config-repo's
        // configs.default is unreachable AND resolution degrades silently,
        // these would come back as 100/60s and every breaker in the system
        // would be ten times less sensitive than intended, with nothing saying
        // so.
        //
        // Documents whichever behaviour is real: see the assertion message if
        // this ever fires.
        var config = circuitBreakerRegistry.circuitBreaker("cart").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize())
                .as("sliding window: 10 means our settings resolved; 100 means the "
                        + "instance silently fell back to Resilience4j's built-in default, "
                        + "which is the failure mode this test exists to catch")
                .isEqualTo(10);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
                .as("wait-in-open: 30s is ours, 60s is the library default")
                .isEqualTo(30_000L);
    }

    @ParameterizedTest(name = "{0}: the breaker judges after 10 calls, not the library's 100")
    @DisplayName("the breaker's minimum number of calls is pinned (H1)")
    @ValueSource(strings = {"cart", "catalog", "customer", "shipping", "order"})
    void theBreakerJudgesAfterTenCalls(String client) {
        // Left unset, Resilience4j waits for 100 calls before it judges at all: a dead downstream would take a
        // hundred failed requests to cut off, and every "N failures leave it closed" test below would pass for the
        // wrong reason (nothing opens a breaker in 20 calls when the minimum is 100).
        assertThat(circuitBreakerRegistry.circuitBreaker(client).getCircuitBreakerConfig().getMinimumNumberOfCalls())
                .as("%s minimum number of calls", client).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0}: one throttled shopper does not shut it for everyone")
    @DisplayName("a refusal by the rate limiter is not a failure of the downstream (H1)")
    @ValueSource(strings = {"cart", "catalog", "customer", "shipping", "order"})
    void aThrottledShopperDoesNotOpenTheBreakerForOthers(String client) {
        // The limiter sits inside the breaker, so its refusal reaches the breaker as an exception. Counted as a
        // failure, a burst from ONE account opened the breaker and gave EVERY shopper a 503 for 30 seconds, though
        // the downstream had not been asked anything.
        var breaker = circuitBreakerRegistry.circuitBreaker(client);
        breaker.reset();
        var refused = io.github.resilience4j.ratelimiter.RequestNotPermitted.createRequestNotPermitted(
                rateLimiterRegistry.rateLimiter(client));

        for (int i = 0; i < 20; i++) {
            breaker.onError(0, java.util.concurrent.TimeUnit.NANOSECONDS, refused);
        }

        assertThat(breaker.getState())
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        breaker.reset();
    }

    @Test
    @DisplayName("retry still refuses to retry a 4xx")
    void retryStillIgnoresClientErrors() {
        // The single most important property of the retry config, and the one
        // that would be lost first if base-config resolution degraded: a
        // malformed body or a failed auth check will not improve on the second
        // attempt, and retrying it turns one client mistake into three requests.
        var config = retryRegistry.retry("cart").getRetryConfig();

        assertThat(config.getMaxAttempts())
                .as("3 attempts is ours; the library default is also 3, so this alone "
                        + "does not prove resolution -- the exception predicate below does")
                .isEqualTo(3);

        var feignClientError = new feign.FeignException.FeignClientException(
                400, "bad request", feign.Request.create(
                        feign.Request.HttpMethod.GET, "http://cart/api/v1/carts/me",
                        java.util.Map.of(), null, null, null),
                null, java.util.Map.of());

        assertThat(config.getExceptionPredicate().test(feignClientError))
                .as("a 4xx must NOT be retried. If this is true, either "
                        + "ignore-exceptions did not resolve or someone removed it")
                .isFalse();
    }

    @ParameterizedTest(name = "{0}: a run of client errors leaves the breaker closed")
    @DisplayName("a 4xx is an answer, not an outage: it never opens a breaker")
    @ValueSource(strings = {"cart", "catalog", "customer", "shipping", "order"})
    void aRunOfClientErrorsLeavesTheBreakerClosed(String client) {
        // Shipping answers 409 for a town the shop has not priced, and order
        // 409 for a piece it cannot hold -- ordinary answers, the downstream
        // working. Counted as failures, ten of them in a window would open
        // the breaker for 30s and give EVERY shopper a 503.
        var breaker = circuitBreakerRegistry.circuitBreaker(client);
        breaker.reset();
        var clientError = new feign.FeignException.FeignClientException(
                409, "town not offered", feign.Request.create(
                        feign.Request.HttpMethod.POST, "http://" + client + "/x",
                        java.util.Map.of(), null, null, null),
                null, java.util.Map.of());

        for (int i = 0; i < 20; i++) {
            breaker.onError(0, java.util.concurrent.TimeUnit.NANOSECONDS, clientError);
        }

        assertThat(breaker.getState())
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        breaker.reset();
    }
}