package com.oriah.payment.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.oriah.payment.exception.PaymentProblem;
import com.oriah.payment.provider.ProviderPaymentChecks;
import com.oriah.payment.provider.zynlepay.ZynlePaySetup;

/**
 * Where ZynlePay sends its "payment done" message (its callback). The one door of payment that needs no token: ZynlePay sends none.
 *
 * <p><b>The message carries no signature, so it proves nothing and is trusted with nothing.</b> It is read for one thing, the reference it names,
 * and all it can do is bring forward the moment Oriah asks ZynlePay itself about that payment ({@link ProviderPaymentChecks#nudge}). The code it
 * carries is never acted on. What a payment becomes rests on ZynlePay's answer to Oriah's own question, and on nothing sent here.
 *
 * <p>Because the door is open to the world: the answer is always the same {@code received}, whatever was sent and whether or not the reference
 * is known (no way to probe for references, and nothing for an unknown sender to retry against); the body is read only up to a small limit and
 * nothing of it is kept or logged but the reference; and an attempt is asked about at most once in ten seconds however many messages name it.
 * The gateway limits how fast any one address may send.
 */
@RestController
@Slf4j
@Tag(name = "ZynlePay callback", description = "ZynlePay's own message that a payment has finished; no token, and it changes nothing by itself")
public class ZynlePayCallbackController {

    public static final String PATH = "/api/v1/payments/zynlepay/callback";
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RECEIVED = "{\"received\":true}";

    private final ProviderPaymentChecks checks;
    private final Clock clock;
    private final int maxBytes;
    private final MeterRegistry meters;

    public ZynlePayCallbackController(ProviderPaymentChecks checks, Clock clock, ZynlePaySetup setup, MeterRegistry meters) {
        this.checks = checks;
        this.clock = clock;
        this.maxBytes = setup.settings().callback().maxBytes();
        this.meters = meters;
    }

    @Operation(operationId = "zynlePayCallback", summary = "ZynlePay says a payment has finished (no token; it only makes Oriah ask ZynlePay sooner)",
            description = "Sent by ZynlePay, as JSON or as a form, naming `reference_no`. It is not signed, so nothing it says is acted on: Oriah asks "
                    + "ZynlePay itself about that payment and acts on that answer. The answer here is always `{\"received\":true}`, whether or not "
                    + "the reference is known.")
    @ApiResponse(responseCode = "200", description = "Received (always, for a body within the size limit)")
    @ApiResponse(responseCode = "413", description = "`payload-too-large`: the body is over the limit and was not read",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> callback(HttpServletRequest request) throws IOException {
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(maxBytes + 1);
        }
        if (body.length > maxBytes) {
            meters.counter("payment.zynlepay.callbacks", "matched", "too-large").increment();
            throw new PaymentProblem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "the message is larger than a ZynlePay callback can be");
        }
        String reference = reference(new String(body, StandardCharsets.UTF_8), request.getContentType());
        if (reference == null) {
            meters.counter("payment.zynlepay.callbacks", "matched", "unreadable").increment();
        } else {
            boolean matched = checks.nudge(reference, clock.instant());
            meters.counter("payment.zynlepay.callbacks", "matched", Boolean.toString(matched)).increment();
            // The reference alone: never the body, and never the code it claims.
            log.info("ZynlePay callback for reference {}: {}", reference, matched ? "a check was brought forward" : "no payment to ask about");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(RECEIVED);
    }

    /** The reference the message names, if it names one that could be one; null otherwise. Nothing else of the message is read. */
    private static String reference(String body, String contentType) {
        String found = null;
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            for (String pair : body.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && isReferenceName(pair.substring(0, equals))) {
                    try {
                        found = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                    break;
                }
            }
        } else {
            try {
                JsonNode root = JSON.readTree(body);
                JsonNode holder = root != null && root.path("response").isObject() ? root.path("response") : root;
                if (holder != null && holder.isObject()) {
                    for (String name : new String[] {"reference_no", "referenceNo", "reference"}) {
                        JsonNode value = holder.get(name);
                        if (value != null && value.isString()) {
                            found = value.asString();
                            break;
                        }
                    }
                }
            } catch (JacksonException e) {
                return null;
            }
        }
        return found != null && REFERENCE.matcher(found).matches() ? found.toUpperCase(java.util.Locale.ROOT) : null;
    }

    private static boolean isReferenceName(String name) {
        return name.equals("reference_no") || name.equals("referenceNo") || name.equals("reference");
    }
}
