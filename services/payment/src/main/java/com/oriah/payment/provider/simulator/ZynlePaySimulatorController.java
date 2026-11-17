package com.oriah.payment.provider.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;

import com.oriah.payment.provider.simulator.ZynlePaySimulatorEngine.Decision;
import com.oriah.payment.provider.simulator.ZynlePaySimulatorEngine.Transaction;
import com.oriah.payment.provider.zynlepay.ZynlePayModeGuard;
import com.oriah.payment.provider.zynlepay.ZynlePaySetup;

/**
 * The doors of the stand-in for ZynlePay ({@link ZynlePaySimulatorEngine}): the two ZynlePay itself has, as far as they are known, and three of
 * the stand-in's own for a test to say how a payment ends.
 *
 * <p><b>It exists only on a stack that is not live and is in SIMULATOR mode</b> ({@link SimulatorCondition}), never beside real credentials. Its
 * doors are under {@code /internal}, which the gateway refuses to everyone, and are not part of the published API ({@code @Hidden}). Inside the
 * service they need no token: ZynlePay would send none.
 */
@RestController
@Hidden
@Slf4j
@Conditional(SimulatorCondition.class)
@RequestMapping(ZynlePayModeGuard.SIMULATOR_PATH)
public class ZynlePaySimulatorController {

    private final ZynlePaySimulatorEngine engine;
    private final String callbackUrl;
    private final Duration loseAnswerFor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ZynlePaySimulatorController(ZynlePaySimulatorEngine engine, ZynlePaySetup setup) {
        this.engine = engine;
        this.callbackUrl = setup.settings().sim().callbackUrl();
        this.loseAnswerFor = setup.settings().sim().loseAnswerFor();
    }

    /** ZynlePay's one JSON door. */
    @PostMapping(path = "/jsonapi", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode api(@RequestBody JsonNode body) throws InterruptedException {
        JsonNode answer = engine.api(body);
        String reference = body.path("data").path("reference_no").asString("");
        if (engine.transaction(reference).map(Transaction::loseTheAnswer).orElse(false) && engine.requests(reference) == 1
                && !"checkPaymentStatus".equals(body.path("data").path("method").asString(""))) {
            // The request was taken; its answer is held back past the caller's patience, as a reply lost on the way would be.
            Thread.sleep(loseAnswerFor.toMillis());
        }
        return answer;
    }

    /** ZynlePay's status door. */
    @PostMapping(path = "/jsonapi/paymentstatus", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode status(@RequestBody JsonNode body) {
        return engine.status(body);
    }

    /** Where a card payment "sends the customer": one line saying what this is. A test decides the payment through {@link #decide}. */
    @GetMapping(path = "/card/{reference}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String card(@PathVariable("reference") String reference) {
        return "This is the stand-in for ZynlePay's card page (payment " + reference + "). No card is asked for here. "
                + "A test decides this payment at POST " + ZynlePayModeGuard.SIMULATOR_PATH + "/transactions/" + reference + "/decide";
    }

    /** Says how a pending payment ends ({@code {"outcome":"PAID"}} or {@code "DECLINED"}), then sends the callbacks ZynlePay would send. */
    @PostMapping(path = "/transactions/{reference}/decide", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> decide(@PathVariable("reference") String reference, @RequestBody Map<String, String> body) {
        String outcome = body.getOrDefault("outcome", "");
        if (!outcome.equals("PAID") && !outcome.equals("DECLINED")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "outcome must be PAID or DECLINED"));
        }
        Optional<Decision> decision = engine.decide(reference, outcome.equals("PAID"));
        if (decision.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        for (int i = 0; i < decision.get().callbacks(); i++) {
            sendCallback(reference, decision.get().responseCode());
        }
        return ResponseEntity.ok(decision.get());
    }

    @GetMapping("/transactions/{reference}")
    public ResponseEntity<?> transaction(@PathVariable("reference") String reference) {
        return engine.transaction(reference)
                .<ResponseEntity<?>>map(tx -> ResponseEntity.ok(Map.of("referenceNo", tx.referenceNo(), "state", tx.state(), "amount", tx.amount(),
                        "requests", engine.requests(reference))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** As ZynlePay's callback is described: a POST naming the reference and the code, with no signature. Sent without waiting for its answer. */
    private void sendCallback(String reference, String code) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reference_no\":\"" + reference + "\",\"response_code\":\"" + code + "\"}")).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            if (failure != null) {
                log.warn("The stand-in's callback for {} was not delivered ({})", reference, failure.getClass().getSimpleName());
            }
        });
    }
}
