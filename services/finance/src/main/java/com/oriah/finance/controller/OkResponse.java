package com.oriah.finance.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * What a read answers. It has to be said: once a call documents any response (the refusals, on the controller), the default "200 with the return type" is no longer added by
 * itself, and the published contract would give a client no type for the answer.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ApiResponse(responseCode = "200", description = "The answer")
public @interface OkResponse {
}
