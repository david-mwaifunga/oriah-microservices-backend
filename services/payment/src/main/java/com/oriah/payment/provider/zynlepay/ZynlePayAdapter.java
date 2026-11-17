package com.oriah.payment.provider.zynlepay;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.oriah.payment.provider.PaymentProvider;
import com.oriah.payment.provider.ZynlePayCodes;
import com.oriah.payment.provider.zynlepay.ZynlePayWire.StatusStyle;

/**
 * The only class that knows ZynlePay's wire (Phase 13). One JSON door, the operation named by {@code data.method}, the credentials in the body.
 *
 * <p><strong>The wire is known second-hand</strong> (two unofficial SDKs; the official documents need a merchant login), so its shape is in
 * {@link ZynlePayWire} and its codes in {@link ZynlePayCodes}, both settings. What is fixed here is how an answer is READ, and that is where money
 * is protected:
 * <ul>
 *   <li>only an HTTP 2xx whose JSON carries a {@code response_code} is ZynlePay's answer; a code inside an error page is not trusted;</li>
 *   <li>"not sent" is said only when no connection was made (refused, no such host, connect timed out);</li>
 *   <li>everything else that is not a listed answer is "unclear" (collect) or "unreadable / no answer" (status), which change nothing;</li>
 *   <li>a card page address is handed on only if it is https on an approved host.</li>
 * </ul>
 *
 * <p>No retry, no circuit breaker: a repeated "collect" would be a second request for money. Nothing here logs a body, a credential or a number;
 * a log line names the reference, the code and the HTTP status.
 */
@Slf4j
public final class ZynlePayAdapter implements PaymentProvider {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ZynlePayWire wire;
    private final ZynlePayCodes codes;
    private final HttpClient http;

    public ZynlePayAdapter(ZynlePayWire wire, ZynlePayCodes codes) {
        this.wire = wire;
        this.codes = codes;
        this.http = HttpClient.newBuilder().connectTimeout(wire.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    // ---------------------------------------------------------------- collect

    @Override
    public Push startMobileMoney(String referenceNo, String msisdnDigits, BigDecimal amount, String currency) {
        ObjectNode data = JSON.createObjectNode();
        data.put("method", "runBillPayment");
        data.put("sender_id", msisdnDigits);
        data.put("reference_no", referenceNo);
        data.put("amount", amount.toPlainString());
        callbackInto(data);
        Reply reply = post(wire.apiPath(), envelope("momo", data), referenceNo, "collect");
        return push(reply, referenceNo, false);
    }

    @Override
    public Push startCard(String referenceNo, BigDecimal amount, String currency, String description, CardPayer payer, String returnUrl) {
        ObjectNode data = JSON.createObjectNode();
        data.put("method", "runTranAuthCapture");
        data.put("reference_no", referenceNo);
        data.put("amount", amount.toPlainString());
        data.put("currency", currency);
        data.put("description", description);
        data.put("first_name", payer.firstName());
        data.put("last_name", payer.lastName());
        data.put("email", payer.email());
        data.put("phone", payer.phone());
        data.put("address", payer.address());
        data.put("city", payer.city());
        data.put("state", payer.city());
        data.put("zip_code", "10101");
        data.put("country", "ZMB");
        if (returnUrl != null && !returnUrl.isBlank()) {
            // Where ZynlePay's page sends the customer when they are done, whichever way it went: the order's own payment page, which reads
            // where the payment stands from Oriah and never trusts the return itself.
            data.put("success_url", returnUrl);
            data.put("fail_url", returnUrl);
        }
        callbackInto(data);
        Reply reply = post(wire.apiPath(), envelope("card", data), referenceNo, "collect by card");
        return push(reply, referenceNo, true);
    }

    private Push push(Reply reply, String referenceNo, boolean card) {
        if (reply.neverLeft()) {
            return new NotSent(reply.detail());
        }
        if (reply.answer() == null) {
            return new Unclear(null, reply.detail());
        }
        JsonNode answer = reply.answer();
        String code = text(answer, "response_code");
        return switch (codes.push(code)) {
            case REFUSED -> new Refused(code);
            case CREDENTIALS -> {
                log.error("ZynlePay refused Oriah itself (code {}) on reference {}: credentials, or this server's address is not allowed", code, referenceNo);
                yield new CredentialsRefused(code);
            }
            case INITIATED -> {
                if (!card) {
                    yield new Accepted(code, text(answer, "transaction_id"), firstText(answer, "operatorreference", "operator_reference"),
                            text(answer, "operator"));
                }
                URI page = approvedPage(text(answer, "redirect_url"));
                if (page == null) {
                    log.error("ZynlePay answered a card request (reference {}) with no page address Oriah may send a customer to", referenceNo);
                    yield new Unclear(code, "no card page address on an approved https host");
                }
                yield new Redirect(code, page, text(answer, "transaction_id"));
            }
            case AMBIGUOUS, UNKNOWN -> new Unclear(code, code == null ? "the answer carried no response_code" : "code " + code + " is not a listed answer");
        };
    }

    // ---------------------------------------------------------------- what became of it

    @Override
    public Status status(String referenceNo) {
        Reply reply;
        if (wire.statusStyle() == StatusStyle.PATH) {
            ObjectNode body = JSON.createObjectNode();
            body.put("api_id", wire.credentials().apiId());
            body.put("api_key", wire.credentials().apiKey());
            body.put("reference_no", referenceNo);
            reply = post(wire.statusPath(), body, referenceNo, "status");
        } else {
            ObjectNode data = JSON.createObjectNode();
            data.put("method", "checkPaymentStatus");
            data.put("reference_no", referenceNo);
            reply = post(wire.apiPath(), envelope("momo", data), referenceNo, "status");
        }
        if (reply.answer() == null) {
            return reply.unreadable() ? new Unreadable(null, reply.detail()) : new NoAnswer(reply.detail());
        }
        JsonNode answer = reply.answer();
        String code = text(answer, "response_code");
        return switch (codes.status(code)) {
            case PAID -> paid(code, answer);
            case PENDING -> new Pending(code);
            case FAILED -> new Failed(code);
            case NOT_FOUND -> new NotFound(code);
            case CREDENTIALS -> new StatusRefused(code);
            case UNREADABLE -> new Unreadable(code, code == null ? "the answer carried no response_code" : "code " + code + " is not a listed answer");
        };
    }

    private static Status paid(String code, JsonNode answer) {
        BigDecimal amount = null;
        JsonNode reported = answer.get("amount");
        if (reported != null && !reported.isNull() && !(reported.isString() && reported.asString().isBlank())) {
            try {
                amount = reported.isNumber() ? reported.decimalValue() : new BigDecimal(reported.asString().strip());
            } catch (NumberFormatException e) {
                // Never guessed: a "paid" whose amount cannot be read is not acted on, and the attempt goes to the owner in time.
                return new Unreadable(code, "paid, with an amount that is not a number");
            }
        }
        String currency = text(answer, "currency");
        return new Paid(code, amount, currency == null ? null : currency.toUpperCase(Locale.ROOT), instant(text(answer, "transaction_date")),
                text(answer, "transaction_id"), firstText(answer, "operatorreference", "operator_reference"), text(answer, "operator"));
    }

    // ---------------------------------------------------------------- the wire

    /**
     * What came back.
     *
     * @param answer     ZynlePay's JSON answer (unwrapped from a {@code response} member if it had one), or null
     * @param neverLeft  no connection was made
     * @param unreadable an HTTP 2xx arrived whose body is not an answer
     */
    private record Reply(JsonNode answer, boolean neverLeft, boolean unreadable, String detail) {
    }

    private ObjectNode envelope(String channel, ObjectNode data) {
        ObjectNode auth = JSON.createObjectNode();
        auth.put("merchant_id", wire.credentials().merchantId());
        auth.put("api_id", wire.credentials().apiId());
        auth.put("api_key", wire.credentials().apiKey());
        auth.put("channel", channel);
        if (wire.serviceId() != null && !wire.serviceId().isBlank()) {
            auth.put("service_id", wire.serviceId());
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("auth", auth);
        body.set("data", data);
        return body;
    }

    private void callbackInto(ObjectNode data) {
        if (wire.callbackUrl() != null && !wire.callbackUrl().isBlank()) {
            data.put("callback_url", wire.callbackUrl());
        }
    }

    private Reply post(String path, ObjectNode body, String referenceNo, String what) {
        HttpRequest request = HttpRequest.newBuilder(wire.baseUrl().resolve(path)).timeout(wire.readTimeout())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(null, false, false, "interrupted while waiting for ZynlePay");
        } catch (IOException | RuntimeException e) {
            boolean neverLeft = noConnectionWasMade(e);
            // The exception's class only: a message can carry an address, never a body, and the class says all that is acted on.
            log.warn("ZynlePay {} for reference {}: {} ({})", what, referenceNo, neverLeft ? "no connection was made" : "no answer", e.getClass().getSimpleName());
            return new Reply(null, neverLeft, false, (neverLeft ? "no connection: " : "no answer: ") + e.getClass().getSimpleName());
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("ZynlePay {} for reference {}: HTTP {}", what, referenceNo, response.statusCode());
            return new Reply(null, false, false, "HTTP " + response.statusCode());
        }
        try {
            JsonNode root = JSON.readTree(response.body());
            JsonNode answer = root != null && root.has("response") && root.get("response").isObject() ? root.get("response") : root;
            if (answer == null || !answer.isObject()) {
                return new Reply(null, false, true, "the answer is not a JSON object");
            }
            log.info("ZynlePay {} for reference {}: code {}", what, referenceNo, text(answer, "response_code"));
            return new Reply(answer, false, false, null);
        } catch (JacksonException e) {
            log.warn("ZynlePay {} for reference {}: HTTP {} with a body that is not JSON", what, referenceNo, response.statusCode());
            return new Reply(null, false, true, "the answer is not JSON");
        }
    }

    /** Whether the failure proves nothing was sent: the connection was refused, the host has no address, or connecting timed out. */
    private static boolean noConnectionWasMade(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException
                    || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** The card page's address if it is one Oriah may send a customer to: https (plain http for the simulator only) on an approved host. */
    private URI approvedPage(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        try {
            URI page = new URI(address.strip());
            String scheme = page.getScheme() == null ? "" : page.getScheme().toLowerCase(Locale.ROOT);
            boolean schemeAllowed = scheme.equals("https") || (wire.plainHttpRedirect() && scheme.equals("http"));
            if (!schemeAllowed || page.getHost() == null || page.getUserInfo() != null) {
                return null;
            }
            // The scheme's own port written out (https on 443) is the same place as no port at all.
            boolean ownPort = page.getPort() < 0 || (scheme.equals("https") && page.getPort() == 443) || (scheme.equals("http") && page.getPort() == 80);
            String host = page.getHost().toLowerCase(Locale.ROOT) + (ownPort ? "" : ":" + page.getPort());
            return wire.redirectHosts().contains(host) ? page : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = text(node, first);
        return value != null ? value : text(node, second);
    }

    /** A member as text whether it came as a string or a number; null when absent, null or blank. */
    private static String text(JsonNode node, String member) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull() || value.isContainer()) {
            return null;
        }
        String text = value.asString().strip();
        return text.isEmpty() ? null : text;
    }

    /** The time ZynlePay gave, when it is written with its offset; anything else is no time, never a guess at a zone. */
    private static Instant instant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
