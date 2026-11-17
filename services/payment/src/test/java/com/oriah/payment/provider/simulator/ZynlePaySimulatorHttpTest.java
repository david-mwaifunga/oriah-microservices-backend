package com.oriah.payment.provider.simulator;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.provider.PaymentProvider;
import com.oriah.payment.provider.PaymentProvider.Accepted;
import com.oriah.payment.provider.PaymentProvider.CardPayer;
import com.oriah.payment.provider.PaymentProvider.Paid;
import com.oriah.payment.provider.PaymentProvider.Pending;
import com.oriah.payment.provider.PaymentProvider.Redirect;
import com.oriah.payment.provider.ProviderReference;
import com.oriah.payment.provider.zynlepay.ZynlePayAdapter;
import com.oriah.payment.provider.zynlepay.ZynlePayModeGuard.Mode;
import com.oriah.payment.provider.zynlepay.ZynlePaySetup;
import com.oriah.payment.provider.zynlepay.ZynlePayWire;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stand-in for ZynlePay, running inside payment and reached over real HTTP by the real adapter: what the tests of a whole payment and the
 * smoke run pay through. It is there only on a stack that is not live and is in SIMULATOR mode, behind doors nobody outside can reach (the
 * gateway refuses everything under {@code /internal}); inside the service those doors need no token, because ZynlePay would send none.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ZynlePaySimulatorHttpTest {

    /** Where the stand-in sends its callbacks in this test: a server of the test's own, standing where the gateway would. */
    private static final WireMockServer CALLBACKS = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void startCallbacks() {
        CALLBACKS.start();
        CALLBACKS.stubFor(post(urlEqualTo("/api/v1/payments/zynlepay/callback")).willReturn(ok("{\"received\":true}")));
    }

    @AfterAll
    static void stopCallbacks() {
        CALLBACKS.stop();
    }

    @DynamicPropertySource
    static void callbackUrl(DynamicPropertyRegistry registry) {
        CALLBACKS.start();
        registry.add("oriah.payment.zynlepay.sim.callback-url", () -> "http://localhost:" + CALLBACKS.port() + "/api/v1/payments/zynlepay/callback");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private ZynlePaySetup setup;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final HttpClient http = HttpClient.newHttpClient();

    /** The adapter the service runs with, pointed at this test's own port (the service's is random here). */
    private PaymentProvider adapter() {
        ZynlePayWire wire = setup.wire();
        return new ZynlePayAdapter(new ZynlePayWire(URI.create("http://localhost:" + port), wire.apiPath(), wire.statusStyle(), wire.statusPath(),
                wire.credentials(), null, null, Set.of("localhost:8085"), true, Duration.ofSeconds(2), Duration.ofSeconds(10)), setup.codes());
    }

    private HttpResponse<String> send(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("with nothing set the service runs on the stand-in, and the provider it pays through is the real adapter")
    void theServiceRunsOnTheStandIn() {
        assertThat(setup.mode()).isEqualTo(Mode.SIMULATOR);
        assertThat(context.getBean(PaymentProvider.class)).isInstanceOf(ZynlePayAdapter.class);
        assertThat(context.getBeansOfType(ZynlePaySimulatorController.class)).hasSize(1);
    }

    @Test
    @DisplayName("mobile money through the real adapter: accepted, still deciding, then paid once it is decided, and a callback is sent to where the gateway would be")
    void mobileMoneyEndToEnd() throws Exception {
        String reference = ProviderReference.mint();
        PaymentProvider adapter = adapter();

        assertThat(adapter.startMobileMoney(reference, "260960000001", new BigDecimal("180.00"), "ZMW")).isInstanceOf(Accepted.class);
        assertThat(adapter.status(reference)).isEqualTo(new Pending("990"));

        HttpResponse<String> decided = send("/internal/zynlepay-sim/transactions/" + reference + "/decide", "{\"outcome\":\"PAID\"}");
        assertThat(decided.statusCode()).isEqualTo(200);
        assertThat(decided.body()).contains("\"responseCode\":\"100\"").contains("\"callbacks\":1");

        assertThat(adapter.status(reference)).isInstanceOfSatisfying(Paid.class, paid -> {
            assertThat(paid.amount()).isEqualByComparingTo("180.00");
            assertThat(paid.currency()).isEqualTo("ZMW");
            assertThat(paid.operator()).isEqualTo("MTN");
        });
        long deadline = System.currentTimeMillis() + 10_000;
        while (CALLBACKS.findAll(postRequestedFor(urlEqualTo("/api/v1/payments/zynlepay/callback"))
                .withRequestBody(matchingJsonPath("$.reference_no", com.github.tomakehurst.wiremock.client.WireMock.equalTo(reference)))).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        CALLBACKS.verify(1, postRequestedFor(urlEqualTo("/api/v1/payments/zynlepay/callback"))
                .withRequestBody(matchingJsonPath("$.reference_no", com.github.tomakehurst.wiremock.client.WireMock.equalTo(reference)))
                .withRequestBody(matchingJsonPath("$.response_code", com.github.tomakehurst.wiremock.client.WireMock.equalTo("100"))));

        HttpResponse<String> seen = get("/internal/zynlepay-sim/transactions/" + reference);
        assertThat(seen.statusCode()).isEqualTo(200);
        assertThat(seen.body()).contains("\"requests\":1").contains("\"state\":\"100\"");
    }

    @Test
    @DisplayName("a card payment answers with the stand-in's own page, which says in a line what it stands for; deciding an unknown reference is 404")
    void cardEndToEnd() throws Exception {
        String reference = ProviderReference.mint();

        PaymentProvider.Push push = adapter().startCard(reference, new BigDecimal("180.00"), "ZMW", "Oriah order 0F9A4C2E",
                new CardPayer("Alice", "Banda", "alice@example.test", null, "Lusaka, Zambia", "Lusaka"), "http://localhost:4000/orders/x/payment?attempt=y");

        assertThat(push).isInstanceOfSatisfying(Redirect.class,
                redirect -> assertThat(redirect.url()).hasToString("http://localhost:8085/internal/zynlepay-sim/card/" + reference));
        HttpResponse<String> page = get("/internal/zynlepay-sim/card/" + reference);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("stand-in").contains(reference);
        assertThat(send("/internal/zynlepay-sim/transactions/OZNOBODYNOBODY00/decide", "{\"outcome\":\"PAID\"}").statusCode()).isEqualTo(404);
        assertThat(send("/internal/zynlepay-sim/transactions/" + reference + "/decide", "{\"outcome\":\"MAYBE\"}").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("the stand-in's doors need no token, and they open nothing else: a shopper's door without a token is still 401")
    void onlyTheStandInIsOpen() throws Exception {
        assertThat(send("/internal/zynlepay-sim/jsonapi/paymentstatus", "{\"api_id\":\"x\",\"api_key\":\"y\",\"reference_no\":\"z\"}").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/payments/contact-info").statusCode()).isEqualTo(401);
        assertThat(get("/internal/anything-else").statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/admin/payments/confirmations/pending").statusCode()).isEqualTo(401);
    }
}
