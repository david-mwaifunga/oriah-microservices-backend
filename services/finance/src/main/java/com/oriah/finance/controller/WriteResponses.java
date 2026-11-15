package com.oriah.finance.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/** What a write answers: 201 the first time, 200 the same request (and Idempotency-Key) again, and 409 when the key was used for a different request or the state refuses it. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ApiResponse(responseCode = "201", description = "Recorded")
@ApiResponse(responseCode = "200", description = "The same request with the same Idempotency-Key again: the answer given the first time")
@ApiResponse(responseCode = "409", description = "The Idempotency-Key was used for a different request, or the state refuses it (the problem type says which)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public @interface WriteResponses {
}
