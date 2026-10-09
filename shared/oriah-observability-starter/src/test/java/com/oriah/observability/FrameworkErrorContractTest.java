package com.oriah.observability;

import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The error contract for exceptions Spring MVC raises <em>itself</em>, as
 * opposed to the domain exceptions covered by
 * {@link ProblemDetailExceptionHandlerTest}.
 *
 * <p>This began life as a Tier-0 characterization test and found a real
 * defect: all six cases below rendered as <strong>500</strong>. The advice
 * declared {@code @ExceptionHandler(Exception.class)} without extending
 * {@code ResponseEntityExceptionHandler}, and
 * {@code ExceptionHandlerExceptionResolver} is consulted before
 * {@code DefaultHandlerExceptionResolver} — so the catch-all claimed
 * {@code HttpRequestMethodNotSupportedException} and friends and flattened
 * 405/415/406/400 into 500.
 *
 * <p>Why that mattered beyond a misleading status line: a 4xx reported as
 * 5xx is <em>retry-eligible</em>. The gateway's {@code retry} filter and
 * checkout's {@code @Retry} would both re-send a permanently-invalid
 * request, and every client typo logged an ERROR with a stack trace. Hence
 * the fix landed in Tier 0.5, ahead of the resilience tiers.
 *
 * <p>{@code standaloneSetup} installs the same three
 * {@code HandlerExceptionResolver}s in the same order as a real dispatcher,
 * so the resolution race reproduces faithfully here.
 */
class FrameworkErrorContractTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ContractController())
            .setControllerAdvice(new ProblemDetailExceptionHandler())
            .build();

    @Test
    @DisplayName("405: wrong HTTP method on an existing path")
    void methodNotAllowed() throws Exception {
        assertStatusAndReport(post("/contract/get-only"), "405 method-not-allowed", 405);
    }

    @Test
    @DisplayName("415: unsupported request content type")
    void unsupportedMediaType() throws Exception {
        assertStatusAndReport(
                post("/contract/json-only").contentType(MediaType.TEXT_PLAIN).content("nope"),
                "415 unsupported-media-type", 415);
    }

    @Test
    @DisplayName("406: no producible representation for the Accept header")
    void notAcceptable() throws Exception {
        assertStatusAndReport(
                get("/contract/text-only").accept(MediaType.APPLICATION_JSON),
                "406 not-acceptable", 406);
    }

    @Test
    @DisplayName("400: malformed JSON body")
    void malformedJson() throws Exception {
        assertStatusAndReport(
                post("/contract/json-only").contentType(MediaType.APPLICATION_JSON).content("{ not json"),
                "400 malformed-json", 400);
    }

    @Test
    @DisplayName("400: missing required query parameter")
    void missingRequiredParam() throws Exception {
        assertStatusAndReport(get("/contract/needs-param"), "400 missing-param", 400);
    }

    @Test
    @DisplayName("400: query parameter of the wrong type")
    void parameterTypeMismatch() throws Exception {
        assertStatusAndReport(get("/contract/needs-int?size=abc"), "400 type-mismatch", 400);
    }

    @Test
    @DisplayName("an exception that carries an HTTP status keeps that status")
    void responseStatusExceptionKeepsItsStatus() throws Exception {
        // Found in production, on the gateway. Spring Cloud Gateway signals an
        // unresolvable downstream with
        //   NotFoundException: 503 SERVICE_UNAVAILABLE "Unable to find instance for checkout"
        // which extends ResponseStatusException and already carries 503. The
        // Exception.class catch-all in this advice outranked Spring's own
        // handling of it, so the edge reported 500 instead -- turning "that
        // service has not registered with Eureka yet" into "the server is
        // broken", and making it retry-eligible on top.
        //
        // This is the same defect class as the 405/415/400 cases below, but it
        // applies to ANY status-carrying exception, so it is worth fixing
        // generically rather than one exception type at a time.
        assertStatusAndReport(get("/contract/service-unavailable"), "503 response-status", 503);
    }

    @Test
    @DisplayName("a 503 keeps its reason as the problem detail")
    void responseStatusExceptionKeepsItsReason() throws Exception {
        MvcResult result = mockMvc.perform(get("/contract/service-unavailable")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body)
                .as("the reason carried by the exception is the only thing telling an "
                        + "operator WHICH downstream was unreachable: %s", body)
                .contains("Unable to find instance for checkout");
    }

    @Test
    @DisplayName("405 advertises the methods that are allowed")
    void methodNotAllowedAdvertisesAllow() throws Exception {
        // RFC 9110: a 405 SHOULD carry Allow. Without it a client cannot
        // discover the correct verb, and the generated TS clients have no way
        // to surface anything better than "something went wrong".
        MvcResult result = mockMvc.perform(post("/contract/get-only")).andReturn();

        assertThat(result.getResponse().getHeader("Allow"))
                .as("Allow header on 405")
                .isNotNull()
                .contains("GET");
    }

    @Test
    @DisplayName("415 advertises the media types that are supported")
    void unsupportedMediaTypeAdvertisesAccept() throws Exception {
        MvcResult result = mockMvc.perform(post("/contract/json-only")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("nope"))
                .andReturn();

        assertThat(result.getResponse().getHeader("Accept"))
                .as("Accept header on 415")
                .isNotNull()
                .contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    @DisplayName("malformed JSON does not leak parser internals to the client")
    void malformedJsonDoesNotLeakParserDetail() throws Exception {
        MvcResult result = mockMvc.perform(post("/contract/json-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Jackson's raw message names the parser class and echoes the payload.
        // That is both noise and a small information leak, so the detail is
        // deliberately generic.
        assertThat(body)
                .as("400 body for malformed JSON: %s", body)
                .doesNotContain("com.fasterxml")
                .doesNotContain("JsonParseException")
                .contains("\"status\":400");
    }

    @Test
    @DisplayName("framework errors carry the traceId, same as domain errors")
    void frameworkErrorsCarryTraceId() throws Exception {
        org.slf4j.MDC.put("traceId", "abcdef00112233445566778899aabbcc");
        try {
            MvcResult result = mockMvc.perform(post("/contract/get-only")).andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .as("a 405 must be as correlatable as a 500 -- it is the "
                            + "status a client is most likely to report")
                    .contains("abcdef00112233445566778899aabbcc");
        } finally {
            org.slf4j.MDC.remove("traceId");
        }
    }

    /**
     * Performs the request, prints status + body (so the real contract is
     * visible in surefire output even when the test passes), then pins the
     * status.
     */
    private void assertStatusAndReport(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String label,
            int expectedStatus) throws Exception {

        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        String contentType = result.getResponse().getContentType();

        System.out.printf("### %-28s -> %d  %s  %s%n", label, status, contentType, body);

        assertThat(status)
                .as("%s: current error contract (body=%s, contentType=%s). "
                        + "A change here is a contract change and must be a reviewed diff.",
                        label, body, contentType)
                .isEqualTo(expectedStatus);
    }

    @RestController
    @RequestMapping("/contract")
    static class ContractController {

        @GetMapping("/get-only")
        String getOnly() {
            return "ok";
        }

        @PostMapping(path = "/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
        String jsonOnly(@RequestBody Payload payload) {
            return payload.title();
        }

        @GetMapping(path = "/text-only", produces = MediaType.TEXT_PLAIN_VALUE)
        String textOnly() {
            return "ok";
        }

        @GetMapping("/service-unavailable")
        String serviceUnavailable() {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to find instance for checkout");
        }

        @GetMapping("/needs-param")
        String needsParam(@RequestParam String code) {
            return code;
        }

        @GetMapping("/needs-int")
        String needsInt(@RequestParam int size) {
            return String.valueOf(size);
        }
    }

    record Payload(@NotBlank String title) {}
}
