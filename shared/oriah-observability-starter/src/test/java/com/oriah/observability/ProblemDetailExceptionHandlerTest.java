package com.oriah.observability;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives every branch of {@link ProblemDetailExceptionHandler} through
 * MockMvc with a tiny throw-by-path test controller. The handler is
 * the only @RestControllerAdvice in the test context (via the
 * @Import below), so each request's exception is routed through it
 * and the response shape (RFC 7807 problem+json with traceId) is
 * asserted.
 */
class ProblemDetailExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ProblemDetailExceptionHandler())
            .build();

    @Test
    void illegalArgument_maps_to_400_bad_request() throws Exception {
        mockMvc.perform(get("/throw/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", equalTo(400)))
                .andExpect(jsonPath("$.title", equalTo("Bad Request")))
                .andExpect(jsonPath("$.detail", equalTo("variant must be UUID")))
                .andExpect(jsonPath("$.type", equalTo("https://oriah.dev/problems/bad-request")));
    }

    @Test
    void illegalState_maps_to_409_conflict() throws Exception {
        mockMvc.perform(get("/throw/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", equalTo(409)))
                .andExpect(jsonPath("$.title", equalTo("Conflict")))
                .andExpect(jsonPath("$.detail", equalTo("Cannot transition CREATED -> SHIPPED")));
    }

    @Test
    void entityNotFound_maps_to_404_not_found() throws Exception {
        mockMvc.perform(get("/throw/entity-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", equalTo(404)))
                .andExpect(jsonPath("$.title", equalTo("Not Found")))
                .andExpect(jsonPath("$.detail", equalTo("order ed98... not found")));
    }

    @Test
    void validationFailure_returns_400_with_field_errors() throws Exception {
        mockMvc.perform(post("/throw/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", equalTo(400)))
                .andExpect(jsonPath("$.title", equalTo("Validation Failed")))
                .andExpect(jsonPath("$.detail", containsString("validation failed")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("title")));
    }

    @Test
    void unhandled_returns_500_with_problem_detail() throws Exception {
        mockMvc.perform(get("/throw/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", equalTo(500)))
                .andExpect(jsonPath("$.title", equalTo("Internal Server Error")));
    }

    @Test
    void traceId_from_mdc_is_echoed_on_responses() throws Exception {
        MDC.put("traceId", "deadbeef0102030405060708090a0b0c");
        try {
            mockMvc.perform(get("/throw/illegal-argument"))
                    .andExpect(jsonPath("$.traceId", equalTo("deadbeef0102030405060708090a0b0c")));
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void missing_traceId_omits_property() throws Exception {
        MDC.remove("traceId");
        mockMvc.perform(get("/throw/illegal-argument"))
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }

    @RestController
    @RequestMapping("/throw")
    static class ThrowingController {

        @org.springframework.web.bind.annotation.GetMapping("/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("variant must be UUID");
        }

        @org.springframework.web.bind.annotation.GetMapping("/illegal-state")
        void illegalState() {
            throw new IllegalStateException("Cannot transition CREATED -> SHIPPED");
        }

        @org.springframework.web.bind.annotation.GetMapping("/entity-not-found")
        void entityNotFound() {
            throw new EntityNotFoundException("order ed98... not found");
        }

        @PostMapping("/validation")
        void validation(@Valid @RequestBody Payload p) {
            // Bean Validation triggers before we get here.
        }

        @org.springframework.web.bind.annotation.GetMapping("/runtime")
        void runtime() {
            throw new RuntimeException("boom");
        }
    }

    record Payload(@NotBlank String title) {}
}
