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
 * CHARACTERIZATION test — records what the current error contract actually
 * does for the exceptions Spring MVC raises <em>itself</em>, as opposed to
 * the domain exceptions covered by {@link ProblemDetailExceptionHandlerTest}.
 *
 * <p>This is a Tier-0 tripwire for the section_12 refactor, not a statement
 * that the current behaviour is desirable. The refactor replaces the
 * standalone {@link ProblemDetailExceptionHandler} with an abstract
 * {@code OriahExceptionHandlerSupport extends ResponseEntityExceptionHandler}
 * plus one concrete {@code @ControllerAdvice} per service. Extending
 * {@code ResponseEntityExceptionHandler} brings in framework-level
 * {@code @ExceptionHandler} methods for exactly the exceptions exercised
 * below, so the mapping <em>will</em> move. This test makes that movement
 * visible as a reviewed diff instead of a silent contract change.
 *
 * <p>The suspected current defect: this advice declares
 * {@code @ExceptionHandler(Exception.class)} without extending
 * {@code ResponseEntityExceptionHandler}.
 * {@code ExceptionHandlerExceptionResolver} is consulted before
 * {@code DefaultHandlerExceptionResolver}, so the catch-all claims
 * {@code HttpRequestMethodNotSupportedException} and friends and renders
 * them as 500 — losing 405/415/406/400 semantics.
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
        assertStatusAndReport(post("/contract/get-only"), "405 method-not-allowed", 500);
    }

    @Test
    @DisplayName("415: unsupported request content type")
    void unsupportedMediaType() throws Exception {
        assertStatusAndReport(
                post("/contract/json-only").contentType(MediaType.TEXT_PLAIN).content("nope"),
                "415 unsupported-media-type", 500);
    }

    @Test
    @DisplayName("406: no producible representation for the Accept header")
    void notAcceptable() throws Exception {
        assertStatusAndReport(
                get("/contract/text-only").accept(MediaType.APPLICATION_JSON),
                "406 not-acceptable", 500);
    }

    @Test
    @DisplayName("400: malformed JSON body")
    void malformedJson() throws Exception {
        assertStatusAndReport(
                post("/contract/json-only").contentType(MediaType.APPLICATION_JSON).content("{ not json"),
                "400 malformed-json", 500);
    }

    @Test
    @DisplayName("400: missing required query parameter")
    void missingRequiredParam() throws Exception {
        assertStatusAndReport(get("/contract/needs-param"), "400 missing-param", 500);
    }

    @Test
    @DisplayName("400: query parameter of the wrong type")
    void parameterTypeMismatch() throws Exception {
        assertStatusAndReport(get("/contract/needs-int?size=abc"), "400 type-mismatch", 500);
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
