package com.oriah.payment.provider.simulator;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.oriah.payment.provider.simulator.ZynlePaySimulatorEngine.Decision;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stand-in for ZynlePay that tests and the smoke run pay through (there is no merchant account, and a test must never touch a real wallet).
 * It answers in ZynlePay's own JSON, and the last digit of the payer's number picks what happens, so one flow can be driven per case:
 *
 * <pre>
 * ...1  accepted; paid or declined when the test decides; one callback
 * ...2  accepted; then "failed" by itself
 * ...3  accepted; "still deciding" for ever
 * ...4  refused at once (code 01)
 * ...5  as 1, and no callback is ever sent
 * ...6  as 1, and the callback is sent three times
 * ...7  as 1, and the amount it reports as paid is K1.00 more
 * ...8  as 1, and the answer to "collect" is lost on the way back (the caller's side decides how)
 * ...9  Oriah itself is refused (code 9901)
 * </pre>
 */
class ZynlePaySimulatorEngineTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ZynlePaySimulatorEngine sim = new ZynlePaySimulatorEngine("M-DEV", "API-ID-DEV", "API-KEY-DEV", "http://localhost:8085/internal/zynlepay-sim");

    private JsonNode collect(String reference, String msisdn, String amount) {
        return sim.api(JSON.readTree("""
                {"auth":{"merchant_id":"M-DEV","api_id":"API-ID-DEV","api_key":"API-KEY-DEV","channel":"momo"},
                 "data":{"method":"runBillPayment","sender_id":"%s","reference_no":"%s","amount":"%s"}}""".formatted(msisdn, reference, amount)));
    }

    private JsonNode card(String reference, String amount) {
        return sim.api(JSON.readTree("""
                {"auth":{"merchant_id":"M-DEV","api_id":"API-ID-DEV","api_key":"API-KEY-DEV","channel":"card"},
                 "data":{"method":"runTranAuthCapture","reference_no":"%s","amount":"%s","currency":"ZMW"}}""".formatted(reference, amount)));
    }

    private JsonNode status(String reference) {
        return sim.status(JSON.readTree("{\"api_id\":\"API-ID-DEV\",\"api_key\":\"API-KEY-DEV\",\"reference_no\":\"" + reference + "\"}"));
    }

    private static String code(JsonNode answer) {
        return answer.get("response_code").asString();
    }

    @Test
    @DisplayName("...1: accepted with 120, 'still deciding' until the test decides, then paid with the amount, and one callback is owed")
    void acceptedThenPaid() {
        JsonNode accepted = collect("OZAAAAAAAAAAAAA1", "260960000001", "180.00");
        assertThat(code(accepted)).isEqualTo("120");
        assertThat(accepted.get("transaction_id").asString()).isNotBlank();
        assertThat(accepted.get("operator").asString()).isEqualTo("MTN");
        assertThat(code(status("OZAAAAAAAAAAAAA1"))).isEqualTo("990");

        Decision decision = sim.decide("OZAAAAAAAAAAAAA1", true).orElseThrow();
        assertThat(decision.callbacks()).isEqualTo(1);
        assertThat(decision.responseCode()).isEqualTo("100");

        JsonNode paid = status("OZAAAAAAAAAAAAA1");
        assertThat(code(paid)).isEqualTo("100");
        assertThat(new BigDecimal(paid.get("amount").asString())).isEqualByComparingTo("180.00");
        assertThat(paid.get("currency").asString()).isEqualTo("ZMW");
        assertThat(paid.get("operatorreference").asString()).isNotBlank();
        // A decision is final: deciding again changes nothing.
        assertThat(sim.decide("OZAAAAAAAAAAAAA1", false).orElseThrow().responseCode()).isEqualTo("100");
    }

    @Test
    @DisplayName("...1 declined by the test: 995")
    void declinedByTheTest() {
        collect("OZAAAAAAAAAAAAB1", "260970000001", "50.00");

        assertThat(sim.decide("OZAAAAAAAAAAAAB1", false).orElseThrow().responseCode()).isEqualTo("995");
        assertThat(code(status("OZAAAAAAAAAAAAB1"))).isEqualTo("995");
    }

    @Test
    @DisplayName("...2 fails by itself, ...3 never decides (not even when told to), ...4 is refused at once and leaves nothing behind")
    void failsNeverDecidesAndRefuses() {
        assertThat(code(collect("OZAAAAAAAAAAAAA2", "260960000002", "10.00"))).isEqualTo("120");
        assertThat(code(status("OZAAAAAAAAAAAAA2"))).isEqualTo("995");

        assertThat(code(collect("OZAAAAAAAAAAAAA3", "260960000003", "10.00"))).isEqualTo("120");
        assertThat(sim.decide("OZAAAAAAAAAAAAA3", true).orElseThrow().responseCode()).isEqualTo("990");
        assertThat(code(status("OZAAAAAAAAAAAAA3"))).isEqualTo("990");

        assertThat(code(collect("OZAAAAAAAAAAAAA4", "260960000004", "10.00"))).isEqualTo("01");
        assertThat(sim.transaction("OZAAAAAAAAAAAAA4")).isEmpty();
        assertThat(code(status("OZAAAAAAAAAAAAA4"))).isEqualTo("404");
    }

    @Test
    @DisplayName("...5 sends no callback, ...6 sends three, ...7 reports K1.00 more than was asked, ...8 asks for the answer to be lost")
    void callbacksAndAmounts() {
        collect("OZAAAAAAAAAAAAA5", "260960000005", "10.00");
        assertThat(sim.decide("OZAAAAAAAAAAAAA5", true).orElseThrow().callbacks()).isZero();

        collect("OZAAAAAAAAAAAAA6", "260960000006", "10.00");
        assertThat(sim.decide("OZAAAAAAAAAAAAA6", true).orElseThrow().callbacks()).isEqualTo(3);

        collect("OZAAAAAAAAAAAAA7", "260960000007", "180.00");
        sim.decide("OZAAAAAAAAAAAAA7", true);
        assertThat(new BigDecimal(status("OZAAAAAAAAAAAAA7").get("amount").asString())).isEqualByComparingTo("181.00");

        assertThat(code(collect("OZAAAAAAAAAAAAA8", "260960000008", "10.00"))).isEqualTo("120");
        assertThat(sim.transaction("OZAAAAAAAAAAAAA8").orElseThrow().loseTheAnswer()).isTrue();
        assertThat(sim.transaction("OZAAAAAAAAAAAAA1")).isEmpty();
    }

    @Test
    @DisplayName("...9 and wrong credentials are refused as ZynlePay refuses Oriah itself; a reference used twice is a duplicate, not a second payment")
    void credentialsAndDuplicates() {
        assertThat(code(collect("OZAAAAAAAAAAAAA9", "260960000009", "10.00"))).isEqualTo("9901");

        JsonNode wrongKey = sim.api(JSON.readTree("""
                {"auth":{"merchant_id":"M-DEV","api_id":"API-ID-DEV","api_key":"nope","channel":"momo"},
                 "data":{"method":"runBillPayment","sender_id":"260960000001","reference_no":"OZAAAAAAAAAAAAC1","amount":"10.00"}}"""));
        assertThat(code(wrongKey)).isEqualTo("9901");
        assertThat(code(sim.status(JSON.readTree("{\"api_id\":\"API-ID-DEV\",\"api_key\":\"nope\",\"reference_no\":\"OZAAAAAAAAAAAAC1\"}")))).isEqualTo("9901");

        assertThat(code(collect("OZAAAAAAAAAAAAD1", "260960000001", "10.00"))).isEqualTo("120");
        assertThat(code(collect("OZAAAAAAAAAAAAD1", "260960000001", "10.00"))).isEqualTo("02");
        assertThat(sim.requests("OZAAAAAAAAAAAAD1")).as("how many times Oriah asked for this money").isEqualTo(2);
    }

    @Test
    @DisplayName("a card request answers with the page a customer would be sent to, and is paid or declined when the test decides")
    void cardPayment() {
        JsonNode answer = card("OZAAAAAAAAAAAAE1", "180.00");

        assertThat(code(answer)).isEqualTo("120");
        assertThat(answer.get("redirect_url").asString()).isEqualTo("http://localhost:8085/internal/zynlepay-sim/card/OZAAAAAAAAAAAAE1");
        assertThat(code(status("OZAAAAAAAAAAAAE1"))).isEqualTo("990");
        assertThat(sim.decide("OZAAAAAAAAAAAAE1", true).orElseThrow().callbacks()).isEqualTo(1);
        assertThat(code(status("OZAAAAAAAAAAAAE1"))).isEqualTo("100");
    }

    @Test
    @DisplayName("the status asked the other way (data.method checkPaymentStatus) answers the same, wrapped in 'response'")
    void statusByMethod() {
        collect("OZAAAAAAAAAAAAF1", "260960000001", "10.00");
        sim.decide("OZAAAAAAAAAAAAF1", true);

        JsonNode answer = sim.api(JSON.readTree("""
                {"auth":{"merchant_id":"M-DEV","api_id":"API-ID-DEV","api_key":"API-KEY-DEV","channel":"momo"},
                 "data":{"method":"checkPaymentStatus","reference_no":"OZAAAAAAAAAAAAF1"}}"""));

        assertThat(code(answer.get("response"))).isEqualTo("100");
    }

    @Test
    @DisplayName("deciding a reference it has never seen decides nothing")
    void anUnknownReference() {
        assertThat(sim.decide("OZNOBODYNOBODY00", true)).isEmpty();
        assertThat(sim.requests("OZNOBODYNOBODY00")).isZero();
    }
}
