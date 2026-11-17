package com.oriah.payment.provider.zynlepay;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.oriah.payment.provider.PaymentProvider;
import com.oriah.payment.provider.PaymentProvider.Accepted;
import com.oriah.payment.provider.PaymentProvider.CardPayer;
import com.oriah.payment.provider.PaymentProvider.CredentialsRefused;
import com.oriah.payment.provider.PaymentProvider.Failed;
import com.oriah.payment.provider.PaymentProvider.NoAnswer;
import com.oriah.payment.provider.PaymentProvider.NotFound;
import com.oriah.payment.provider.PaymentProvider.NotSent;
import com.oriah.payment.provider.PaymentProvider.Paid;
import com.oriah.payment.provider.PaymentProvider.Pending;
import com.oriah.payment.provider.PaymentProvider.Redirect;
import com.oriah.payment.provider.PaymentProvider.Refused;
import com.oriah.payment.provider.PaymentProvider.StatusRefused;
import com.oriah.payment.provider.PaymentProvider.Unclear;
import com.oriah.payment.provider.PaymentProvider.Unreadable;
import com.oriah.payment.provider.ZynlePayCodes;
import com.oriah.payment.provider.zynlepay.ZynlePayWire.StatusStyle;
import com.oriah.payment.provider.zynlepay.ZynlePayWire.ZynlePayCredentials;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one class that speaks ZynlePay's wire, over real sockets against a local server, with the client the service runs with.
 *
 * <p>The wire is known second-hand (two unofficial SDKs), so what is pinned here is what Oriah SENDS and how it READS each kind of answer. The
 * rules that protect money do not depend on the wire being right: anything that is not a listed answer leaves the payment pending.
 */
class ZynlePayAdapterTest {

    private static final String API = "/zynlepay/jsonapi";
    private static final String STATUS = "/zynlepay/jsonapi/paymentstatus";
    private static final ZynlePayCodes CODES = new ZynlePayCodes(List.of("120"), List.of("100"), List.of("990"), List.of("995"), List.of("01"),
            List.of("9900-9915"), List.of("02"), List.of("404"));

    private final WireMockServer zynlePay = new WireMockServer(options().dynamicPort());
    private PaymentProvider adapter;

    @BeforeEach
    void start() {
        zynlePay.start();
        adapter = adapter(StatusStyle.PATH, "http://localhost:" + zynlePay.port(), Duration.ofSeconds(2), Duration.ofMillis(600));
    }

    @AfterEach
    void stop() {
        zynlePay.stop();
    }

    private static PaymentProvider adapter(StatusStyle style, String baseUrl, Duration connect, Duration read) {
        return new ZynlePayAdapter(new ZynlePayWire(URI.create(baseUrl), API, style, STATUS, new ZynlePayCredentials("M-1", "API-ID-1", "API-KEY-SECRET"),
                null, null, Set.of("pay.zynlepay.test"), false, connect, read), CODES);
    }

    private void apiAnswers(String body) {
        zynlePay.stubFor(post(urlEqualTo(API)).willReturn(okJson(body)));
    }

    private void statusAnswers(String body) {
        zynlePay.stubFor(post(urlEqualTo(STATUS)).willReturn(okJson(body)));
    }

    private PaymentProvider.Push collect() {
        return adapter.startMobileMoney("OZ7K2M9Q4T8X1B5D", "260961234567", new BigDecimal("180.00"), "ZMW");
    }

    private PaymentProvider.Push collectByCard() {
        return adapter.startCard("OZ7K2M9Q4T8X1B5D", new BigDecimal("180.00"), "ZMW", "Oriah order 0F9A4C2E",
                new CardPayer("Alice", "Banda", "alice@example.test", "260961234567", "Lusaka, Zambia", "Lusaka"));
    }

    // ---------------------------------------------------------------- collect: mobile money

    @Test
    @DisplayName("mobile money: exactly this is sent, and 120 means the customer is being asked, with ZynlePay's own ids kept")
    void mobileMoneyAccepted() {
        apiAnswers("""
                {"response_code":"120","response_description":"Transaction is initiated","reference_no":"OZ7K2M9Q4T8X1B5D",
                 "transaction_id":884213,"operatorreference":5512,"operator":"MTN"}""");

        assertThat(collect()).isEqualTo(new Accepted("120", "884213", "5512", "MTN"));

        zynlePay.verify(1, postRequestedFor(urlEqualTo(API)).withRequestBody(equalToJson("""
                {"auth":{"merchant_id":"M-1","api_id":"API-ID-1","api_key":"API-KEY-SECRET","channel":"momo"},
                 "data":{"method":"runBillPayment","sender_id":"260961234567","reference_no":"OZ7K2M9Q4T8X1B5D","amount":"180.00"}}""", false, false)));
    }

    @Test
    @DisplayName("an answer wrapped in a 'response' member is read the same way")
    void theWrappedForm() {
        apiAnswers("""
                {"response":{"response_code":"120","transaction_id":"T-9","operator_reference":"OP-1"}}""");

        assertThat(collect()).isEqualTo(new Accepted("120", "T-9", "OP-1", null));
    }

    @Test
    @DisplayName("only a code listed as a refusal refuses; a credentials code says Oriah itself was turned away")
    void refusals() {
        apiAnswers("{\"response_code\":\"01\",\"response_description\":\"Subscriber not found\"}");
        assertThat(collect()).isEqualTo(new Refused("01"));

        apiAnswers("{\"response_code\":\"9901\"}");
        assertThat(collect()).isEqualTo(new CredentialsRefused("9901"));
    }

    @Test
    @DisplayName("a duplicate reference, a code nobody listed, a 500, a reply that is not JSON or has no code: none is a refusal")
    void whatIsNeverARefusal() {
        apiAnswers("{\"response_code\":\"02\",\"response_description\":\"Duplicate reference\"}");
        assertThat(collect()).isInstanceOfSatisfying(Unclear.class, u -> assertThat(u.code()).isEqualTo("02"));

        apiAnswers("{\"response_code\":\"777\"}");
        assertThat(collect()).isInstanceOfSatisfying(Unclear.class, u -> assertThat(u.code()).isEqualTo("777"));

        zynlePay.stubFor(post(urlEqualTo(API)).willReturn(aResponse().withStatus(500).withBody("{\"response_code\":\"01\"}")));
        assertThat(collect()).as("a refusal code inside an HTTP 500 is not trusted").isInstanceOf(Unclear.class);

        zynlePay.stubFor(post(urlEqualTo(API)).willReturn(aResponse().withStatus(200).withBody("<html>Service Temporarily Unavailable</html>")));
        assertThat(collect()).isInstanceOf(Unclear.class);

        apiAnswers("{\"status\":\"ok\"}");
        assertThat(collect()).isInstanceOf(Unclear.class);

        // "paid" straight from the push is nobody's verified answer.
        apiAnswers("{\"response_code\":\"100\"}");
        assertThat(collect()).isInstanceOf(Unclear.class);
    }

    @Test
    @DisplayName("a reply that never comes, or breaks off, is unclear: the request was sent and may be at ZynlePay")
    void aLostReply() {
        zynlePay.stubFor(post(urlEqualTo(API)).willReturn(okJson("{\"response_code\":\"120\"}").withFixedDelay(2_000)));
        assertThat(collect()).isInstanceOf(Unclear.class);

        zynlePay.stubFor(post(urlEqualTo(API)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(collect()).isInstanceOf(Unclear.class);

        zynlePay.stubFor(post(urlEqualTo(API)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        assertThat(collect()).isInstanceOf(Unclear.class);
        // And it was asked once each time: the adapter never repeats a request for money by itself.
        zynlePay.verify(3, postRequestedFor(urlEqualTo(API)));
    }

    @Test
    @DisplayName("nothing listening, or a host that does not exist: nothing was sent, and that alone is 'not sent'")
    void notSent() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PaymentProvider nobodyThere = adapter(StatusStyle.PATH, "http://localhost:" + closedPort, Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThat(nobodyThere.startMobileMoney("OZ7K2M9Q4T8X1B5D", "260961234567", new BigDecimal("180.00"), "ZMW")).isInstanceOf(NotSent.class);

        PaymentProvider noSuchHost = adapter(StatusStyle.PATH, "http://zynlepay.invalid", Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThat(noSuchHost.startMobileMoney("OZ7K2M9Q4T8X1B5D", "260961234567", new BigDecimal("180.00"), "ZMW")).isInstanceOf(NotSent.class);
        // The status call has no "not sent": no answer is no answer.
        assertThat(nobodyThere.status("OZ7K2M9Q4T8X1B5D")).isInstanceOf(NoAnswer.class);
    }

    // ---------------------------------------------------------------- collect: card

    @Test
    @DisplayName("card: the customer is sent to ZynlePay's own page, and Oriah sends no card detail of any kind")
    void cardRedirect() {
        apiAnswers("{\"response_code\":\"120\",\"redirect_url\":\"https://pay.zynlepay.test/checkout/abc123\",\"transaction_id\":\"T-77\"}");

        assertThat(collectByCard()).isEqualTo(new Redirect("120", URI.create("https://pay.zynlepay.test/checkout/abc123"), "T-77"));

        zynlePay.verify(1, postRequestedFor(urlEqualTo(API)).withRequestBody(equalToJson("""
                {"auth":{"merchant_id":"M-1","api_id":"API-ID-1","api_key":"API-KEY-SECRET","channel":"card"},
                 "data":{"method":"runTranAuthCapture","reference_no":"OZ7K2M9Q4T8X1B5D","amount":"180.00","currency":"ZMW",
                         "description":"Oriah order 0F9A4C2E","first_name":"Alice","last_name":"Banda","email":"alice@example.test",
                         "phone":"260961234567","address":"Lusaka, Zambia","city":"Lusaka","state":"Lusaka","zip_code":"10101","country":"ZMB"}}""",
                false, false)));
        String sent = zynlePay.getAllServeEvents().getFirst().getRequest().getBodyAsString().toLowerCase();
        assertThat(sent).doesNotContain("cardnumber", "card_number", "pan", "cvv", "cvc", "expiry");
    }

    @Test
    @DisplayName("a card page on a host nobody approved, or not https, is not handed to the customer")
    void aCardPageElsewhereIsWithheld() {
        apiAnswers("{\"response_code\":\"120\",\"redirect_url\":\"https://evil.example/checkout/abc123\"}");
        assertThat(collectByCard()).isInstanceOf(Unclear.class);

        apiAnswers("{\"response_code\":\"120\",\"redirect_url\":\"http://pay.zynlepay.test/checkout/abc123\"}");
        assertThat(collectByCard()).isInstanceOf(Unclear.class);

        apiAnswers("{\"response_code\":\"120\",\"redirect_url\":\"https://pay.zynlepay.test@evil.example/x\"}");
        assertThat(collectByCard()).isInstanceOf(Unclear.class);

        apiAnswers("{\"response_code\":\"120\"}");
        assertThat(collectByCard()).as("no page address at all").isInstanceOf(Unclear.class);

        apiAnswers("{\"response_code\":\"01\",\"redirect_url\":\"https://pay.zynlepay.test/checkout/abc123\"}");
        assertThat(collectByCard()).as("a refusal is a refusal whatever else it carries").isEqualTo(new Refused("01"));
    }

    // ---------------------------------------------------------------- what became of it

    @Test
    @DisplayName("status, by its own path: three flat members are sent, and 100 is money with what ZynlePay reported about it")
    void statusPaid() {
        statusAnswers("""
                {"response_code":"100","reference_no":"OZ7K2M9Q4T8X1B5D","amount":"180.00","currency":"ZMW","transaction_id":884213,
                 "operatorreference":5512,"operator":"MTN","transaction_date":"2026-11-17T10:02:11Z"}""");

        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isEqualTo(new Paid("100", new BigDecimal("180.00"), "ZMW", Instant.parse("2026-11-17T10:02:11Z"),
                "884213", "5512", "MTN"));

        zynlePay.verify(1, postRequestedFor(urlEqualTo(STATUS)).withRequestBody(equalToJson(
                "{\"api_id\":\"API-ID-1\",\"api_key\":\"API-KEY-SECRET\",\"reference_no\":\"OZ7K2M9Q4T8X1B5D\"}", false, false)));
    }

    @Test
    @DisplayName("a 'paid' that reports no amount is still paid; one whose amount cannot be read is not acted on at all")
    void statusPaidAmounts() {
        statusAnswers("{\"response_code\":\"100\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isEqualTo(new Paid("100", null, null, null, null, null, null));

        statusAnswers("{\"response_code\":\"100\",\"amount\":180}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isInstanceOfSatisfying(Paid.class, p -> assertThat(p.amount()).isEqualByComparingTo("180.00"));

        statusAnswers("{\"response_code\":\"100\",\"amount\":\"K180\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isInstanceOf(Unreadable.class);

        // A time it cannot read is simply no time: the day the money is dated falls back to when Oriah saw it paid.
        statusAnswers("{\"response_code\":\"100\",\"transaction_date\":\"17/11/2026 12:02\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isInstanceOfSatisfying(Paid.class, p -> assertThat(p.paidAt()).isNull());
    }

    @Test
    @DisplayName("990 is pending, 995 failed, a listed 'no such reference' is that, and a code nobody listed is unreadable: never failed")
    void statusOthers() {
        statusAnswers("{\"response_code\":\"990\",\"message\":\"retry after 60 seconds\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isEqualTo(new Pending("990"));

        statusAnswers("{\"response_code\":\"995\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isEqualTo(new Failed("995"));

        statusAnswers("{\"response_code\":\"404\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isEqualTo(new NotFound("404"));

        statusAnswers("{\"response_code\":\"9902\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isEqualTo(new StatusRefused("9902"));

        statusAnswers("{\"response_code\":\"555\"}");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isInstanceOfSatisfying(Unreadable.class, u -> assertThat(u.code()).isEqualTo("555"));

        statusAnswers("not json");
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isInstanceOf(Unreadable.class);

        zynlePay.stubFor(post(urlEqualTo(STATUS)).willReturn(aResponse().withStatus(502).withBody("{\"response_code\":\"995\"}")));
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).as("a 'failed' inside an HTTP 502 is not ZynlePay's answer").isInstanceOf(NoAnswer.class);

        zynlePay.stubFor(post(urlEqualTo(STATUS)).willReturn(okJson("{\"response_code\":\"100\"}").withFixedDelay(2_000)));
        assertThat(adapter.status("OZ7K2M9Q4T8X1B5D")).isInstanceOf(NoAnswer.class);
    }

    @Test
    @DisplayName("status, the other way the API is described: the usual envelope with data.method checkPaymentStatus, at the one door")
    void statusByMethod() {
        PaymentProvider byMethod = adapter(StatusStyle.METHOD, "http://localhost:" + zynlePay.port(), Duration.ofSeconds(2), Duration.ofSeconds(2));
        apiAnswers("{\"response\":{\"response_code\":\"100\",\"amount\":\"180.00\",\"currency\":\"ZMW\"}}");

        assertThat(byMethod.status("OZ7K2M9Q4T8X1B5D")).isEqualTo(new Paid("100", new BigDecimal("180.00"), "ZMW", null, null, null, null));

        zynlePay.verify(1, postRequestedFor(urlEqualTo(API)).withRequestBody(equalToJson("""
                {"auth":{"merchant_id":"M-1","api_id":"API-ID-1","api_key":"API-KEY-SECRET","channel":"momo"},
                 "data":{"method":"checkPaymentStatus","reference_no":"OZ7K2M9Q4T8X1B5D"}}""", false, false)));
    }

    // ---------------------------------------------------------------- what must never be written down

    @Test
    @DisplayName("no answer and no credentials object ever prints the key, and no answer carries a whole reply")
    void nothingPrintsTheKey() {
        apiAnswers("{\"response_code\":\"777\",\"echo\":\"API-KEY-SECRET\",\"sender_id\":\"260961234567\"}");
        PaymentProvider.Push unclear = collect();

        assertThat(unclear.toString()).doesNotContain("API-KEY-SECRET").doesNotContain("260961234567");
        assertThat(new ZynlePayCredentials("M-1", "API-ID-1", "API-KEY-SECRET").toString()).doesNotContain("API-KEY-SECRET").doesNotContain("API-ID-1")
                .doesNotContain("M-1");
    }
}
