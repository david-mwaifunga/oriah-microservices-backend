package com.oriah.payment.provider.simulator;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stand-in for ZynlePay, for tests and the smoke run only: there is no merchant account yet, and a test must never touch a real wallet. It
 * answers in ZynlePay's own JSON as far as that is known, so the real adapter talks to it unchanged.
 *
 * <p>It exists only in SIMULATOR mode, never beside real credentials. What it knows is kept in memory: a restart forgets it, and a payment asked
 * about afterwards is "no such reference", which Oriah treats as still pending.
 *
 * <p>The last digit of the payer's number picks what happens (a card request behaves as {@code ...1}):
 * <pre>
 * ...1  accepted; paid or declined when told to ({@link #decide}); one callback
 * ...2  accepted; then "failed" by itself
 * ...3  accepted; "still deciding" for ever, whatever it is told
 * ...4  refused at once (code 01)
 * ...5  as 1, and no callback
 * ...6  as 1, and three callbacks
 * ...7  as 1, and the amount it reports as paid is K1.00 more than was asked
 * ...8  as 1, and the answer to "collect" is to be lost on its way back (the HTTP layer holds it past the caller's patience)
 * ...9  Oriah itself is refused (code 9901)
 * </pre>
 * Any other digit behaves as {@code ...1}.
 */
public final class ZynlePaySimulatorEngine {

    static final String INITIATED = "120";
    static final String PAID = "100";
    static final String PENDING = "990";
    static final String FAILED = "995";
    static final String REFUSED = "01";
    static final String DUPLICATE = "02";
    static final String NOT_FOUND = "404";
    static final String CREDENTIALS = "9901";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One request for money as the simulator remembers it. */
    public record Transaction(String referenceNo, char scenario, boolean card, BigDecimal amount, String currency, String state, long transactionId,
                              long operatorReference, String operator) {

        /** Whether the answer to "collect" should never reach the caller. */
        public boolean loseTheAnswer() {
            return scenario == '8';
        }

        Transaction decided(String newState) {
            return new Transaction(referenceNo, scenario, card, amount, currency, newState, transactionId, operatorReference, operator);
        }
    }

    /** What a decision came to: the code a callback would carry, and how many callbacks are to be sent. */
    public record Decision(String referenceNo, String responseCode, int callbacks) {
    }

    private final String merchantId;
    private final String apiId;
    private final String apiKey;
    private final String ownBaseUrl;
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(880_000);

    public ZynlePaySimulatorEngine(String merchantId, String apiId, String apiKey, String ownBaseUrl) {
        this.merchantId = merchantId;
        this.apiId = apiId;
        this.apiKey = apiKey;
        this.ownBaseUrl = ownBaseUrl;
    }

    /** The one JSON door: {@code data.method} names the operation. */
    public JsonNode api(JsonNode body) {
        JsonNode auth = body.path("auth");
        JsonNode data = body.path("data");
        String reference = data.path("reference_no").asString("");
        String method = data.path("method").asString("");
        if (!merchantId.equals(auth.path("merchant_id").asString("")) || !credentials(auth)) {
            return answer(CREDENTIALS, "Invalid credentials", reference);
        }
        return switch (method) {
            case "runBillPayment" -> collect(reference, data.path("sender_id").asString(""), data.path("amount").asString(""), "ZMW", false);
            case "runTranAuthCapture" -> collect(reference, "1", data.path("amount").asString(""), data.path("currency").asString("ZMW"), true);
            case "checkPaymentStatus" -> {
                ObjectNode wrapped = JSON.createObjectNode();
                wrapped.set("response", statusOf(reference));
                yield wrapped;
            }
            default -> answer("9999", "Unknown method", reference);
        };
    }

    /** The status call by its own path: {@code {api_id, api_key, reference_no}}. */
    public JsonNode status(JsonNode body) {
        String reference = body.path("reference_no").asString("");
        return credentials(body) ? statusOf(reference) : answer(CREDENTIALS, "Invalid credentials", reference);
    }

    /** Tells a pending request how it ends. A request that has ended, or one of the never-deciding kind, stays as it is. */
    public Optional<Decision> decide(String referenceNo, boolean paid) {
        Transaction now = transactions.computeIfPresent(referenceNo,
                (ref, tx) -> tx.state().equals(PENDING) && tx.scenario() != '3' ? tx.decided(paid ? PAID : FAILED) : tx);
        if (now == null) {
            return Optional.empty();
        }
        int callbacks = switch (now.scenario()) {
            case '3', '5' -> 0;
            case '6' -> 3;
            default -> 1;
        };
        return Optional.of(new Decision(referenceNo, now.state(), now.state().equals(PENDING) ? 0 : callbacks));
    }

    public Optional<Transaction> transaction(String referenceNo) {
        return Optional.ofNullable(transactions.get(referenceNo));
    }

    /** How many times this money was asked for: a test's proof that a request was never repeated. */
    public int requests(String referenceNo) {
        AtomicInteger count = requests.get(referenceNo);
        return count == null ? 0 : count.get();
    }

    // ---------------------------------------------------------------- plumbing

    private JsonNode collect(String reference, String senderId, String amount, String currency, boolean card) {
        requests.computeIfAbsent(reference, r -> new AtomicInteger()).incrementAndGet();
        char scenario = senderId.isEmpty() ? '1' : senderId.charAt(senderId.length() - 1);
        if (scenario == '9') {
            return answer(CREDENTIALS, "Invalid credentials", reference);
        }
        if (scenario == '4') {
            return answer(REFUSED, "Subscriber cannot be charged", reference);
        }
        BigDecimal asked;
        try {
            asked = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            return answer(REFUSED, "Invalid amount", reference);
        }
        Transaction created = new Transaction(reference, scenario, card, asked, currency, scenario == '2' ? FAILED : PENDING, ids.incrementAndGet(),
                ids.incrementAndGet(), card ? "CARD" : operatorOf(senderId));
        if (transactions.putIfAbsent(reference, created) != null) {
            return answer(DUPLICATE, "Duplicate reference", reference);
        }
        ObjectNode answer = answer(INITIATED, "Transaction is initiated", reference);
        answer.put("transaction_id", created.transactionId());
        if (card) {
            answer.put("redirect_url", ownBaseUrl + "/card/" + reference);
        } else {
            answer.put("operatorreference", created.operatorReference());
            answer.put("operator", created.operator());
            answer.put("sender_id", senderId);
        }
        return answer;
    }

    private ObjectNode statusOf(String reference) {
        Transaction tx = transactions.get(reference);
        if (tx == null) {
            return answer(NOT_FOUND, "No such reference", reference);
        }
        ObjectNode answer = answer(tx.state(), switch (tx.state()) {
            case PAID -> "Successful";
            case FAILED -> "Failed";
            default -> "Transaction is being processed, retry after 60 seconds";
        }, reference);
        if (tx.state().equals(PAID)) {
            answer.put("amount", (tx.scenario() == '7' ? tx.amount().add(BigDecimal.ONE) : tx.amount()).toPlainString());
            answer.put("currency", tx.currency());
            answer.put("transaction_id", tx.transactionId());
            answer.put("operatorreference", tx.operatorReference());
            answer.put("operator", tx.operator());
        }
        return answer;
    }

    private boolean credentials(JsonNode holder) {
        return apiId.equals(holder.path("api_id").asString("")) && apiKey.equals(holder.path("api_key").asString(""));
    }

    private static String operatorOf(String senderId) {
        if (senderId.length() < 5) {
            return "MTN";
        }
        return switch (senderId.substring(3, 5)) {
            case "97", "77" -> "AIRTEL";
            case "95", "75" -> "ZAMTEL";
            default -> "MTN";
        };
    }

    private static ObjectNode answer(String code, String description, String reference) {
        ObjectNode answer = JSON.createObjectNode();
        answer.put("response_code", code);
        answer.put("response_description", description);
        answer.put("reference_no", reference);
        return answer;
    }
}
